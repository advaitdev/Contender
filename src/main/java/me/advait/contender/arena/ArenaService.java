package me.advait.contender.arena;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import me.advait.contender.Contender;
import me.advait.contender.core.Module;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.BlockBounds;
import me.advait.contender.map.MapManager;
import me.advait.contender.util.YamlStorage;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Owns the arena world and every map's pool of pasted copies.
 *
 * Copies are pasted one at a time in the background. A duel leases a READY copy; between rounds it asks
 * for a reset, which only pastes again if blocks changed. Released copies are pasted again before reuse
 * when they are dirty, otherwise their loose entities are cleared and they are ready immediately.
 */
public final class ArenaService extends Module {
    private final MapManager maps;
    private ArenaChunks chunks;
    private World world;
    private int spacing;
    private final Map<String, List<ArenaCopy>> pools = new LinkedHashMap<>();
    private final Map<String, Clipboard> templates = new HashMap<>();
    private final Map<String, CompletableFuture<Clipboard>> templateLoads = new HashMap<>();
    private final Map<String, String> templateFailures = new HashMap<>();
    private final Set<String> saving = new HashSet<>();
    private final Map<ArenaCopy, List<CompletableFuture<Void>>> urgent = new LinkedHashMap<>();
    private final Map<Integer, String> trusted = new HashMap<>();
    private ArenaCopy pasting;
    private boolean closed;

    public ArenaService(Contender plugin, MapManager maps) {
        super(plugin);
        this.maps = maps;
    }

    @Override protected void onEnable() {
        closed = false;
        chunks = new ArenaChunks(plugin);
        String name = plugin.getConfig().getString("arenas.world", "contender_arenas");
        if (name.equals(plugin.getLobby().worldName()) || maps.getMaps().stream().anyMatch(map -> map.getWorldName().equals(name))) {
            throw new IllegalStateException("The arena world must be separate from the lobby and the source maps.");
        }
        spacing = Math.max(256, plugin.getConfig().getInt("arenas.spacing", 1024));
        world = new WorldCreator(name).generator(new VoidGenerator()).generateStructures(false).createWorld();
        if (world == null) throw new IllegalStateException("Could not load the arena world " + name + ".");
        var spacingKey = new org.bukkit.NamespacedKey(plugin, "arena-spacing");
        Integer savedSpacing = world.getPersistentDataContainer().get(spacingKey, org.bukkit.persistence.PersistentDataType.INTEGER);
        if (savedSpacing != null && savedSpacing != spacing) {
            plugin.getLogger().warning("The arena world was created with spacing " + savedSpacing + "; using it instead of " + spacing + ".");
            spacing = savedSpacing;
        }
        world.getPersistentDataContainer().set(spacingKey, org.bukkit.persistence.PersistentDataType.INTEGER, spacing);
        applyRules(world);
        readState();
        for (ArenaMap map : maps.getMaps()) rebuildPool(map);
        tasks.repeat(10, 5, this::work);
    }

    @Override protected void onDisable() {
        closed = true;
        // Paper saves every world right after plugins disable, so copies can be trusted on a clean stop.
        boolean stopping = plugin.getServer().isStopping();
        writeState(stopping);
        if (chunks != null) chunks.close();
        urgent.values().forEach(list -> list.forEach(future -> future.completeExceptionally(new IllegalStateException("Arenas stopped."))));
        urgent.clear();
    }

    public static void applyRules(World world) {
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.SPAWN_MONSTERS, false);
        world.setGameRule(GameRules.LOCATOR_BAR, false);
        world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        world.setGameRule(GameRules.SHOW_DEATH_MESSAGES, false);
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRules.KEEP_INVENTORY, true);
        world.setGameRule(GameRules.PVP, true);
        world.setGameRule(GameRules.SPAWN_PHANTOMS, false);
        world.setGameRule(GameRules.SPAWN_PATROLS, false);
        world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
        world.setTime(6000);
        world.setStorm(false);
        world.setThundering(false);
    }

    // ---- Queries -------------------------------------------------------------------------------

    public World world() { return world; }
    public boolean isArenaWorld(World candidate) { return world != null && world.equals(candidate); }
    public List<ArenaCopy> copies(String mapId) { return List.copyOf(pools.getOrDefault(mapId, List.of())); }
    /** Copies {@link #lease} would hand out right now. */
    public int ready(String mapId) {
        if (closed || saving.contains(mapId)) return 0;
        return (int) copies(mapId).stream().filter(c -> c.status() == ArenaCopy.Status.READY && !c.retired()).count();
    }
    public boolean isSaving(String mapId) { return saving.contains(mapId); }

    public ArenaCopy copyAt(Location location) {
        if (location == null || !isArenaWorld(location.getWorld())) return null;
        for (List<ArenaCopy> pool : pools.values()) {
            for (ArenaCopy copy : pool) if (copy.contains(location)) return copy;
        }
        return null;
    }

    /** A one-line status for dialogs and error messages. */
    public String readiness(String mapId) {
        ArenaMap map = maps.getMap(mapId);
        if (map == null) return "That map no longer exists.";
        if (map.getBounds() == null) return "Save the map selection first.";
        if (!map.isComplete()) return "Set both team spawns to prepare this map.";
        if (saving.contains(mapId)) return "Saving the map. Copies will prepare afterwards.";
        List<ArenaCopy> copies = copies(mapId);
        int ready = ready(mapId), inUse = (int) copies.stream().filter(c -> c.status() == ArenaCopy.Status.IN_USE).count();
        StringBuilder text = new StringBuilder(ready + " of " + copies.size() + " ready");
        if (inUse > 0) text.append(", ").append(inUse).append(" in use");
        long waiting = copies.stream().filter(c -> c.status() == ArenaCopy.Status.WAITING || c.status() == ArenaCopy.Status.PASTING).count();
        if (waiting > 0) text.append(", ").append(waiting).append(" preparing");
        String failure = templateFailures.get(mapId);
        if (failure == null) failure = copies.stream().map(ArenaCopy::failure).filter(Objects::nonNull).findFirst().orElse(null);
        if (failure != null) text.append(". Retrying: ").append(failure);
        return text.append('.').toString();
    }

    // ---- Leases --------------------------------------------------------------------------------

    /** Takes a READY copy, or returns null when none is free. */
    public ArenaCopy lease(String mapId, ArenaActivity user) {
        if (closed || saving.contains(mapId)) return null;
        for (ArenaCopy copy : pools.getOrDefault(mapId, List.of())) {
            if (copy.status() == ArenaCopy.Status.READY && !copy.retired()) {
                copy.status(ArenaCopy.Status.IN_USE);
                copy.user(user);
                trusted.remove(copy.slot());
                return copy;
            }
        }
        return null;
    }

    /** Records the game using a leased copy, once that game exists. */
    public void bind(ArenaCopy copy, ArenaActivity user) {
        if (copy.status() == ArenaCopy.Status.IN_USE) copy.user(user);
    }

    /**
     * Prepares a leased copy for the next round. Completes immediately when no blocks changed;
     * otherwise the template is pasted again with priority over background work.
     */
    public CompletableFuture<Void> resetForRound(ArenaCopy copy) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Arenas stopped."));
        ArenaMap template = maps.getMap(copy.mapId());
        boolean stale = template == null || !Objects.equals(template.getSchematic(), copy.pastedSchematic());
        if (!copy.isDirty() && !stale) {
            clearEntities(copy);
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        urgent.computeIfAbsent(copy, ignored -> new ArrayList<>()).add(done);
        work();
        return done;
    }

    /** Returns a copy to the pool once its occupants have left. */
    public void release(ArenaCopy copy) {
        if (copy.status() != ArenaCopy.Status.IN_USE) return;
        copy.user(null);
        List<CompletableFuture<Void>> waiting = urgent.remove(copy);
        if (pasting == copy) {
            // A round reset is still running; paste again once it finishes rather than lending a half-pasted copy.
            if (waiting != null) waiting.forEach(future -> future.completeExceptionally(new IllegalStateException("The match ended.")));
            copy.markDirty();
            copy.status(ArenaCopy.Status.WAITING);
            return;
        }
        if (waiting != null) waiting.forEach(future -> future.completeExceptionally(new IllegalStateException("The match ended.")));
        if (copy.retired()) { pools.getOrDefault(copy.mapId(), new ArrayList<>()).remove(copy); return; }
        ArenaMap template = maps.getMap(copy.mapId());
        if (template == null) { pools.getOrDefault(copy.mapId(), new ArrayList<>()).remove(copy); return; }
        copy.relayout(ArenaLayout.translate(template, copy));
        if (copy.isDirty() || !Objects.equals(template.getSchematic(), copy.pastedSchematic())) {
            copy.status(ArenaCopy.Status.WAITING);
        } else {
            clearEntities(copy);
            copy.status(ArenaCopy.Status.READY);
        }
    }

    // ---- Background preparation ----------------------------------------------------------------

    private void work() {
        if (closed || pasting != null) return;
        long now = System.currentTimeMillis();
        ArenaCopy next = null;
        boolean forRound = false;
        for (ArenaCopy candidate : List.copyOf(urgent.keySet())) {
            if (candidate.status() != ArenaCopy.Status.IN_USE) continue;
            String mapId = candidate.mapId();
            String broken = maps.getMap(mapId) == null ? "The map was deleted."
                    : templateFailures.containsKey(mapId) && !templateRetryDue(mapId, now) ? templateFailures.get(mapId) : null;
            if (broken != null) {
                // Fail the reset now (the match plays on after a few tries) instead of blocking every other copy.
                List<CompletableFuture<Void>> waiting = urgent.remove(candidate);
                if (waiting != null) waiting.forEach(future -> future.completeExceptionally(new IllegalStateException(broken)));
                continue;
            }
            next = candidate;
            forRound = true;
            break;
        }
        if (next == null) next = nextBackgroundCopy(now);
        if (next == null) return;
        ArenaMap template = maps.getMap(next.mapId());
        if (template == null) return;
        Clipboard clipboard = template(template);
        if (clipboard == null) return;
        paste(next, template, clipboard, forRound);
    }

    private ArenaCopy nextBackgroundCopy(long now) {
        ArenaCopy best = null;
        int bestReady = Integer.MAX_VALUE;
        for (var entry : pools.entrySet()) {
            if (saving.contains(entry.getKey()) || templateFailures.containsKey(entry.getKey()) && !templateRetryDue(entry.getKey(), now)) continue;
            int readyCount = ready(entry.getKey());
            for (ArenaCopy copy : entry.getValue()) {
                boolean needsPaste = copy.status() == ArenaCopy.Status.WAITING
                        || copy.status() == ArenaCopy.Status.FAILED && copy.retryDue(now);
                if (needsPaste && !copy.retired() && empty(copy) && readyCount < bestReady) {
                    best = copy;
                    bestReady = readyCount;
                    break;
                }
            }
        }
        return best;
    }

    private final Map<String, Long> templateRetry = new HashMap<>();
    private boolean templateRetryDue(String mapId, long now) { return now >= templateRetry.getOrDefault(mapId, 0L); }

    /** Returns the loaded template, or null while it loads. */
    private Clipboard template(ArenaMap map) {
        String schematic = map.getSchematic();
        if (schematic != null) {
            Clipboard loaded = templates.get(schematic);
            if (loaded != null) return loaded;
        }
        if (templateLoads.containsKey(map.getId())) return null;
        // After a failed load, wait for the retry time instead of trying again every few ticks.
        if (templateFailures.containsKey(map.getId()) && !templateRetryDue(map.getId(), System.currentTimeMillis())) return null;
        CompletableFuture<Clipboard> load = schematic == null ? capture(map) : readSchematic(schematic);
        templateLoads.put(map.getId(), load);
        load.whenComplete((clipboard, failure) -> {
            templateLoads.remove(map.getId());
            if (failure != null) {
                templateFailures.put(map.getId(), reason(failure));
                templateRetry.put(map.getId(), System.currentTimeMillis() + 30_000);
                plugin.getLogger().log(Level.WARNING, "Could not load the template for map " + map.getId() + ": " + reason(failure));
                return;
            }
            templateFailures.remove(map.getId());
            if (map.getSchematic() != null) templates.put(map.getSchematic(), clipboard);
        });
        return null;
    }

    private void paste(ArenaCopy copy, ArenaMap template, Clipboard clipboard, boolean forRound) {
        pasting = copy;
        String schematic = template.getSchematic();
        if (!forRound) copy.status(ArenaCopy.Status.PASTING);
        BlockBounds bounds = copy.layout().getBounds();
        long started = System.nanoTime();
        chunks.run(world, bounds, () -> {
            clearAllEntities(copy);
            var target = BukkitAdapter.adapt(world);
            return async(() -> {
                try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(target).changeSetNull().build()) {
                    Operations.complete(new ClipboardHolder(clipboard).createPaste(session)
                            .to(BlockVector3.at(bounds.minX(), bounds.minY(), bounds.minZ()))
                            .ignoreAirBlocks(false).copyEntities(true).copyBiomes(false).build());
                }
                return null;
            });
        }).whenComplete((ignored, failure) -> {
            pasting = null;
            if (closed) return;
            List<CompletableFuture<Void>> waiting = forRound ? urgent.remove(copy) : null;
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING, "Could not paste " + copy.mapId() + " copy " + copy.slot() + ": " + reason(failure));
                if (forRound) {
                    if (waiting != null) waiting.forEach(future -> future.completeExceptionally(failure));
                } else copy.failed(reason(failure), System.currentTimeMillis());
                return;
            }
            copy.pasted(schematic);
            if (forRound) {
                if (waiting != null) waiting.forEach(future -> future.complete(null));
            } else if (copy.status() == ArenaCopy.Status.PASTING) {
                copy.status(ArenaCopy.Status.READY);
                trusted.put(copy.slot(), schematic);
            }
            long millis = (System.nanoTime() - started) / 1_000_000;
            if (millis > 2000) plugin.getLogger().info("Pasted " + copy.mapId() + " copy " + copy.slot() + " in " + millis + " ms.");
            tasks.later(1, this::work);
        });
    }

    private boolean empty(ArenaCopy copy) {
        for (Player player : world.getPlayers()) if (copy.contains(player.getLocation())) return false;
        return true;
    }

    /** Removes every entity in a copy before pasting it again. Players and Contender's own displays stay. */
    private void clearAllEntities(ArenaCopy copy) {
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Player || me.advait.contender.util.Tags.isManaged(entity)) continue;
            if (copy.contains(entity.getLocation())) entity.remove();
        }
    }

    /**
     * Removes what a match leaves lying around (items, arrows, pearls, XP, primed TNT, falling blocks and the like)
     * without a paste. The map's own item frames, paintings, armor stands and displays stay.
     */
    public void clearEntities(ArenaCopy copy) {
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Player || me.advait.contender.util.Tags.isManaged(entity)) continue;
            if (!loose(entity) || !copy.contains(entity.getLocation())) continue;
            entity.remove();
        }
    }

    private static boolean loose(Entity entity) {
        return entity instanceof org.bukkit.entity.Item || entity instanceof org.bukkit.entity.Projectile
                || entity instanceof org.bukkit.entity.ExperienceOrb || entity instanceof org.bukkit.entity.TNTPrimed
                || entity instanceof org.bukkit.entity.FallingBlock || entity instanceof org.bukkit.entity.AreaEffectCloud
                || entity instanceof org.bukkit.entity.EvokerFangs || entity instanceof org.bukkit.entity.EnderCrystal
                || entity instanceof org.bukkit.entity.Vehicle || entity instanceof org.bukkit.entity.Tameable
                || entity instanceof org.bukkit.entity.Mannequin;
    }

    // ---- Map editing ---------------------------------------------------------------------------

    /** Creates the pool for a complete map, or refreshes it after its spawns, name or copy count changed. */
    public void rebuildPool(ArenaMap map) {
        if (world == null) return;
        if (!map.isComplete()) { retirePool(map.getId()); return; }
        try { ArenaLayout.validate(map, spacing, world.getMinHeight(), world.getMaxHeight()); }
        catch (IllegalArgumentException invalid) {
            templateFailures.put(map.getId(), invalid.getMessage());
            plugin.getLogger().warning("Map " + map.getId() + " cannot be used: " + invalid.getMessage());
            return;
        }
        templateFailures.remove(map.getId());
        int first = maps.allocateSlots(map);
        List<ArenaCopy> pool = pools.computeIfAbsent(map.getId(), ignored -> new ArrayList<>());
        Set<Integer> present = new HashSet<>();
        for (ArenaCopy copy : pool) {
            present.add(copy.slot());
            if (copy.slot() - first >= map.getCopies()) {
                copy.retire();
            } else if (copy.status() != ArenaCopy.Status.IN_USE && copy.status() != ArenaCopy.Status.PASTING) {
                copy.relayout(ArenaLayout.translate(map, copy));
                if (!Objects.equals(map.getSchematic(), copy.pastedSchematic()) && copy.status() == ArenaCopy.Status.READY) {
                    copy.status(ArenaCopy.Status.WAITING);
                }
            }
        }
        pool.removeIf(copy -> copy.retired() && copy.status() != ArenaCopy.Status.IN_USE && copy.status() != ArenaCopy.Status.PASTING);
        for (int i = 0; i < map.getCopies(); i++) {
            if (present.contains(first + i)) continue;
            ArenaCopy copy = ArenaLayout.place(map, world.getName(), first + i, spacing, world.getMinHeight(), world.getMaxHeight());
            String saved = trusted.get(copy.slot());
            if (saved != null && saved.equals(map.getSchematic())) {
                copy.pasted(saved);
                copy.status(ArenaCopy.Status.READY);
            }
            pool.add(copy);
        }
        pool.sort(Comparator.comparingInt(ArenaCopy::slot));
    }

    private void retirePool(String mapId) {
        List<ArenaCopy> pool = pools.get(mapId);
        if (pool == null) return;
        pool.forEach(ArenaCopy::retire);
        pool.removeIf(copy -> copy.status() != ArenaCopy.Status.IN_USE && copy.status() != ArenaCopy.Status.PASTING);
        if (pool.isEmpty()) pools.remove(mapId);
    }

    public void requireEditable(ArenaMap map) {
        if (saving.contains(map.getId())) throw new IllegalStateException("This map is still saving.");
        if (copies(map.getId()).stream().anyMatch(copy -> copy.status() == ArenaCopy.Status.IN_USE)) {
            throw new IllegalStateException("Wait for this map's matches to finish before editing it.");
        }
    }

    public void setSpawn(ArenaMap map, String which, Location location) {
        if (isArenaWorld(location.getWorld())) throw new IllegalArgumentException("Stand in the original map, not an arena copy.");
        if (!map.contains(location)) throw new IllegalArgumentException("Stand inside the map's saved region.");
        switch (which) {
            case "1" -> map.setTeam1Spawn(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            case "2" -> map.setTeam2Spawn(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            case "spectator" -> map.setSpectatorSpawn(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            default -> throw new IllegalArgumentException("Choose team 1, team 2, or spectator.");
        }
        maps.save(map);
        rebuildPool(map);
    }

    public void configure(ArenaMap map, String name, int copies) {
        if (name.isBlank() || name.length() > 64) throw new IllegalArgumentException("Choose a map name between 1 and 64 characters.");
        map.setDisplayName(name.strip());
        map.setCopies(copies);
        maps.save(map);
        rebuildPool(map);
    }

    public void delete(ArenaMap map) {
        requireEditable(map);
        retirePool(map.getId());
        maps.delete(map.getId());
    }

    /** Saves the player's WorldEdit selection as a new map. */
    public CompletableFuture<ArenaMap> create(Player player, String name, int copies) {
        String id = MapManager.idFor(name);
        if (id.isEmpty()) return CompletableFuture.failedFuture(new IllegalArgumentException("Include a letter or number in the map name."));
        if (maps.mapExists(id) || saving.contains(id)) return CompletableFuture.failedFuture(new IllegalArgumentException("A map with that name already exists."));
        if (isArenaWorld(player.getWorld())) return CompletableFuture.failedFuture(new IllegalArgumentException("Build and select the original map outside the arena world."));
        ArenaMap map = new ArenaMap(id);
        try {
            var actor = BukkitAdapter.adapt(player);
            var selection = WorldEdit.getInstance().getSessionManager().get(actor).getSelection(actor.getWorld());
            if (!(selection instanceof CuboidRegion)) throw new IllegalArgumentException("Select a cuboid with the WorldEdit wand.");
            map.setDisplayName(name.strip());
            map.setCopies(copies);
            map.setWorldName(player.getWorld().getName());
            var min = selection.getMinimumPoint();
            var max = selection.getMaximumPoint();
            map.setRollbackRegion(min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
            BlockBounds bounds = map.getBounds();
            if (bounds.volume() > plugin.getConfig().getLong("arenas.max-template-blocks", 100_000_000L)) {
                throw new IllegalArgumentException("The selection is larger than arenas.max-template-blocks in config.yml.");
            }
            if (bounds.width() > spacing - 2 * ArenaLayout.MARGIN || bounds.length() > spacing - 2 * ArenaLayout.MARGIN) {
                throw new IllegalArgumentException("The selection is wider than the arena spacing allows (" + (spacing - 2 * ArenaLayout.MARGIN) + " blocks).");
            }
        } catch (com.sk89q.worldedit.IncompleteRegionException incomplete) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Select both corners with the WorldEdit wand first."));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        saving.add(id);
        return capture(map).thenApply(clipboard -> {
            templates.put(map.getSchematic(), clipboard);
            maps.save(map);
            return map;
        }).whenComplete((ignored, failure) -> saving.remove(id));
    }

    /** Captures the map's region again, for example after editing a course or arena. */
    public CompletableFuture<Void> saveBlocks(ArenaMap map) {
        if (saving.contains(map.getId())) return CompletableFuture.failedFuture(new IllegalStateException("This map is already saving."));
        saving.add(map.getId());
        String previous = map.getSchematic();
        return capture(map).thenAccept(clipboard -> {
            templates.put(map.getSchematic(), clipboard);
            maps.save(map);
            Clipboard old = previous == null ? null : templates.remove(previous);
            if (old != null && old != clipboard) old.close();
            rebuildPool(map);
        }).whenComplete((ignored, failure) -> saving.remove(map.getId()));
    }

    private CompletableFuture<Clipboard> capture(ArenaMap map) {
        World source;
        try {
            source = Bukkit.getWorld(map.getWorldName());
            if (source == null) source = plugin.getManagedWorlds().loadExisting(map.getWorldName());
        } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        if (source == null) return CompletableFuture.failedFuture(new IllegalStateException("Could not load the map's world."));
        if (isArenaWorld(source)) return CompletableFuture.failedFuture(new IllegalStateException("The original map must be outside the arena world."));
        World loaded = source;
        BlockBounds bounds = map.getBounds();
        String fileName = map.getId() + "-" + UUID.randomUUID().toString().substring(0, 8) + ".schem";
        return chunks.run(loaded, bounds, () -> {
            var sourceWorld = BukkitAdapter.adapt(loaded);
            return async(() -> {
                CuboidRegion region = new CuboidRegion(sourceWorld, BlockVector3.at(bounds.minX(), bounds.minY(), bounds.minZ()),
                        BlockVector3.at(bounds.maxX(), bounds.maxY(), bounds.maxZ()));
                Clipboard clipboard = new BlockArrayClipboard(region);
                clipboard.setOrigin(region.getMinimumPoint());
                try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(sourceWorld).changeSetNull().build()) {
                    ForwardExtentCopy copy = new ForwardExtentCopy(session, region, clipboard, region.getMinimumPoint());
                    copy.setCopyingEntities(true);
                    copy.setCopyingBiomes(true);
                    Operations.complete(copy);
                }
                for (var entity : new ArrayList<>(clipboard.getEntities())) {
                    var state = entity.getState();
                    if (state != null && state.getType().getId().equals("minecraft:player")) clipboard.removeEntity(entity);
                }
                Path path = schematicPath(fileName);
                Files.createDirectories(path.getParent());
                try (var output = Files.newOutputStream(path);
                     var writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(output)) {
                    writer.write(clipboard);
                }
                return clipboard;
            });
        }).thenApply(clipboard -> { map.setSchematic(fileName); return clipboard; });
    }

    private CompletableFuture<Clipboard> readSchematic(String name) {
        return async(() -> {
            try (var input = Files.newInputStream(schematicPath(name));
                 var reader = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getReader(input)) {
                return reader.read();
            }
        });
    }

    private Path schematicPath(String name) {
        if (!name.matches("[a-zA-Z0-9_-]+\\.schem")) throw new IllegalArgumentException("Invalid schematic file name.");
        return plugin.getDataFolder().toPath().resolve("maps").resolve(name);
    }

    @FunctionalInterface private interface Job<T> { T run() throws Exception; }

    /** Runs on a worker thread and completes on the server thread. */
    private <T> CompletableFuture<T> async(Job<T> job) {
        CompletableFuture<T> result = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            T value = null;
            Throwable error = null;
            try { value = job.run(); } catch (Throwable failure) { error = failure; }
            T finalValue = value;
            Throwable finalError = error;
            if (!plugin.isEnabled()) { result.completeExceptionally(new IllegalStateException("Plugin stopped.")); return; }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (finalError != null) result.completeExceptionally(finalError);
                else result.complete(finalValue);
            });
        });
        return result;
    }

    private static String reason(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    // ---- Restart reuse -------------------------------------------------------------------------

    /** Copies recorded during a clean shutdown are reused; anything else is pasted again. */
    private void readState() {
        File file = new File(plugin.getDataFolder(), "arena-state.yml");
        trusted.clear();
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (yaml.getBoolean("clean-shutdown") && yaml.getString("world", "").equals(world.getName()) && yaml.getInt("spacing") == spacing) {
            var section = yaml.getConfigurationSection("copies");
            if (section != null) for (String key : section.getKeys(false)) {
                try { trusted.put(Integer.parseInt(key), section.getString(key)); }
                catch (NumberFormatException ignored) { }
            }
        }
        // A crash before the next clean shutdown must not trust this record.
        YamlConfiguration consumed = new YamlConfiguration();
        consumed.set("clean-shutdown", false);
        YamlStorage.save(consumed, file);
    }

    private void writeState(boolean clean) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("clean-shutdown", clean);
        if (world != null) {
            yaml.set("world", world.getName());
            yaml.set("spacing", spacing);
        }
        if (clean) {
            for (List<ArenaCopy> pool : pools.values()) for (ArenaCopy copy : pool) {
                if (copy.status() == ArenaCopy.Status.READY && copy.pastedSchematic() != null) {
                    yaml.set("copies." + copy.slot(), copy.pastedSchematic());
                }
            }
        }
        try { YamlStorage.save(yaml, new File(plugin.getDataFolder(), "arena-state.yml")); }
        catch (RuntimeException failure) { plugin.getLogger().log(Level.WARNING, "Could not save arena-state.yml", failure); }
    }
}
