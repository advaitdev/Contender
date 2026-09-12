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
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.BlockBounds;
import me.advait.contender.map.MapManager;
import me.advait.contender.map.SpawnPoint;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Owns the arena world, saved templates, and prebuilt copies. */
public final class ArenaManager {
    private final Contender plugin;
    private final MapManager maps;
    private final ArenaChunks chunks;
    private final Map<String, List<ArenaInstance>> pools = new LinkedHashMap<>();
    private final Map<String, Clipboard> clipboards = new HashMap<>();
    private final Set<String> editing = new HashSet<>();
    private final Set<CompletableFuture<?>> pending = new HashSet<>();
    private CompletableFuture<Void> work = CompletableFuture.completedFuture(null);
    private World world;
    private boolean closed;
    private int spacing;

    public ArenaManager(Contender plugin, MapManager maps) {
        this.plugin = plugin;
        this.maps = maps;
        this.chunks = new ArenaChunks(plugin);
    }

    public void initialize() {
        String name = plugin.getConfig().getString("arenas.world", "contender_arenas");
        if (name.equals(plugin.getLobbyManager().getLobbyWorldName())
                || maps.getMaps().stream().anyMatch(map -> map.getWorldName().equals(name))) {
            throw new IllegalArgumentException("The arena world must be separate from the lobby and source maps.");
        }
        spacing = Math.max(128, plugin.getConfig().getInt("arenas.spacing", 1024));
        world = new WorldCreator(name).generator(new VoidGenerator()).generateStructures(false).createWorld();
        if (world == null) throw new IllegalStateException("Could not load the arena world.");
        var spacingKey = new org.bukkit.NamespacedKey(plugin, "arena-spacing");
        Integer savedSpacing = world.getPersistentDataContainer().get(spacingKey, org.bukkit.persistence.PersistentDataType.INTEGER);
        if (savedSpacing != null && savedSpacing != spacing) {
            throw new IllegalArgumentException("This arena world uses spacing " + savedSpacing + ". Keep that spacing or choose a new arena world.");
        }
        world.getPersistentDataContainer().set(spacingKey, org.bukkit.persistence.PersistentDataType.INTEGER, spacing);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(org.bukkit.GameRules.LOCATOR_BAR, false);
        world.setGameRule(org.bukkit.GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        world.setStorm(false);
        world.setThundering(false);
        for (ArenaMap map : maps.getMaps()) {
            if (!map.isComplete()) continue;
            prepare(map).exceptionally(failure -> {
                if (!closed) plugin.getLogger().log(Level.SEVERE, "Could not prepare map " + map.getId(), failure);
                return null;
            });
        }
    }

    public World getWorld() { return world; }
    public boolean isArenaWorld(World candidate) { return world != null && world.equals(candidate); }
    public boolean isEditing(String mapId) { return editing.contains(mapId); }
    public boolean hasPendingWork() { return !work.isDone() || !editing.isEmpty() || pools.keySet().stream().anyMatch(this::isBusy); }
    public void reloadTemplates() {
        if (hasPendingWork()) throw new IllegalStateException("Wait for matches and arena preparation to finish.");
        pools.clear();
        clipboards.values().forEach(Clipboard::close);
        clipboards.clear();
        initialize();
    }
    public List<ArenaInstance> instances(String mapId) { return List.copyOf(pools.getOrDefault(mapId, List.of())); }
    public int available(String mapId) {
        return (int) instances(mapId).stream().filter(a -> a.status() == ArenaInstance.Status.READY).count();
    }
    public boolean isBusy(String mapId) {
        return isEditing(mapId) || instances(mapId).stream().anyMatch(a -> a.isReserved()
                || a.status() == ArenaInstance.Status.PREPARING || a.status() == ArenaInstance.Status.RESETTING);
    }
    public ArenaLease acquire(String mapId) {
        if (closed || isEditing(mapId)) return null;
        for (ArenaInstance instance : instances(mapId)) {
            ArenaLease lease = instance.acquire();
            if (lease != null) return lease;
        }
        return null;
    }
    public void release(ArenaLease lease) {
        if (lease == null || !lease.instance().owns(lease)) return;
        if (closed) lease.instance().failed();
        lease.instance().release(lease);
    }
    public void discard(ArenaLease lease) {
        if (lease != null && lease.instance().owns(lease)) lease.instance().failed();
    }
    /** Releases a cancelled game's copy without waiting for a reset. Rebuild it before reuse. */
    public void abandon(ArenaLease lease) {
        if (lease == null || !lease.instance().owns(lease)) return;
        lease.instance().failed();
        lease.instance().release(lease);
    }
    public ArenaInstance at(Location location) {
        if (!isArenaWorld(location.getWorld())) return null;
        for (List<ArenaInstance> pool : pools.values()) {
            for (ArenaInstance instance : pool) {
                if (instance.map().contains(location)) return instance;
            }
        }
        return null;
    }

    public CompletableFuture<ArenaMap> create(Player player, String id, String name, int copies) {
        if (!id.matches("[a-z0-9_-]{1,40}")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Use letters, numbers, - or _ for the map ID."));
        }
        if (maps.mapExists(id) || editing.contains(id)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("That map ID is already in use."));
        }
        if (isArenaWorld(player.getWorld())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Select the original map outside the arena world."));
        }
        try {
            var actor = BukkitAdapter.adapt(player);
            var selection = WorldEdit.getInstance().getSessionManager().get(actor).getSelection(actor.getWorld());
            if (!(selection instanceof CuboidRegion)) throw new IllegalArgumentException("Select a cuboid with the WorldEdit wand.");
            ArenaMap map = new ArenaMap(id);
            map.setDisplayName(name.isBlank() ? id : name.strip());
            map.setCopies(copies);
            map.setWorldName(player.getWorld().getName());
            var min = selection.getMinimumPoint();
            var max = selection.getMaximumPoint();
            map.setRollbackRegion(min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
            validateBounds(map);
            editing.add(id);
            return capture(map).thenApply(clipboard -> {
                clipboards.put(id, clipboard);
                maps.save(map);
                return map;
            }).whenComplete((mapResult, failure) -> editing.remove(id));
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    failure instanceof com.sk89q.worldedit.IncompleteRegionException
                            ? "Select both corners with the WorldEdit wand first." : failure.getMessage(), failure));
        }
    }

    public void setSpawn(ArenaMap map, String team, Location location) {
        requireEditable(map);
        if (!map.contains(location)) throw new IllegalArgumentException("Stand inside the source map's selected region.");
        switch (team) {
            case "1" -> map.setTeam1Spawn(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            case "2" -> map.setTeam2Spawn(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            case "spectator" -> map.setSpectatorSpawn(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            default -> throw new IllegalArgumentException("Choose team 1, team 2, or spectator.");
        }
        pools.remove(map.getId());
        maps.save(map);
    }

    public void configure(ArenaMap map, String name, int copies) {
        requireEditable(map);
        if (name.isBlank() || name.length() > 64) throw new IllegalArgumentException("Choose a map name between 1 and 64 characters.");
        map.setCopies(copies);
        map.setDisplayName(name.strip());
        pools.remove(map.getId());
        maps.save(map);
    }

    public CompletableFuture<Void> saveBlocks(ArenaMap map) {
        requireEditable(map);
        editing.add(map.getId());
        pools.remove(map.getId());
        return capture(map).thenAccept(clipboard -> {
            Clipboard old = clipboards.put(map.getId(), clipboard);
            if (old != null) old.close();
            maps.save(map);
        }).whenComplete((ignored, failure) -> editing.remove(map.getId()));
    }

    private void requireEditable(ArenaMap map) {
        if (isBusy(map.getId())) throw new IllegalStateException("Wait for this map's matches and arena preparation to finish.");
        for (ArenaInstance instance : instances(map.getId())) requireEmpty(instance);
    }

    private void requireEmpty(ArenaInstance instance) {
        if (world == null) return;
        for (Player player : world.getPlayers()) {
            Location location = player.getLocation();
            if (instance.cell().containsColumn(location.getX(), location.getZ())) {
                throw new IllegalStateException("Move everyone out of this arena copy before rebuilding it.");
            }
        }
    }

    public CompletableFuture<Void> prepare(ArenaMap map) {
        try {
            requireEditable(map);
            validateBounds(map);
            if (!map.isComplete()) throw new IllegalArgumentException("Set both team spawns first.");
            validateSpawn(map, map.getTeam1Point());
            validateSpawn(map, map.getTeam2Point());
            if (map.getSpectatorPoint() != null) validateSpawn(map, map.getSpectatorPoint());
            int first = maps.allocateSlots(map);
            List<ArenaInstance> pool = new ArrayList<>();
            for (int i = 0; i < map.getCopies(); i++) {
                pool.add(ArenaLayout.place(map, world.getName(), first + i, spacing, world.getMinHeight(), world.getMaxHeight()));
            }
            pools.put(map.getId(), pool);
            editing.add(map.getId());
            CompletableFuture<Clipboard> loaded;
            if (clipboards.containsKey(map.getId())) loaded = CompletableFuture.completedFuture(clipboards.get(map.getId()));
            else if (map.getSchematic() == null) loaded = capture(map).thenApply(clipboard -> { maps.save(map); return clipboard; });
            else loaded = enqueue(() -> async(() -> {
                try (var input = Files.newInputStream(schematicPath(map.getSchematic()));
                     var reader = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getReader(input)) {
                    return reader.read();
                }
            }));
            return loaded.thenCompose(clipboard -> {
                clipboards.put(map.getId(), clipboard);
                List<CompletableFuture<Void>> prepared = new ArrayList<>();
                for (ArenaInstance instance : pool) prepared.add(restore(instance, clipboard));
                return CompletableFuture.allOf(prepared.toArray(CompletableFuture[]::new));
            }).whenComplete((ignored, failure) -> {
                editing.remove(map.getId());
                if (failure != null) pool.forEach(ArenaInstance::failed);
            });
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    public CompletableFuture<Void> reset(ArenaLease lease) {
        if (closed || lease == null || !lease.instance().owns(lease)) return CompletableFuture.failedFuture(new IllegalStateException("Arena reservation expired."));
        if (lease.instance().status() == ArenaInstance.Status.FAILED) return CompletableFuture.failedFuture(
                new IllegalStateException("Rebuild this arena copy before using it again."));
        Clipboard clipboard = clipboards.get(lease.instance().map().getId());
        if (clipboard == null) return CompletableFuture.failedFuture(new IllegalStateException("Map template is not loaded."));
        return restore(lease.instance(), clipboard);
    }

    private CompletableFuture<Void> restore(ArenaInstance instance, Clipboard clipboard) {
        long revision = instance.beginReset();
        return enqueue(() -> {
            requireReset(instance, revision);
            return chunks.run(world, instance.map().getBounds(), () -> {
                requireReset(instance, revision);
                requireEmpty(instance);
                // Remove entities across this slot, including those that wandered beyond the selection.
                for (var entity : world.getEntities()) {
                    if (entity instanceof Player) continue;
                    Location location = entity.getLocation();
                    if (instance.cell().containsColumn(location.getX(), location.getZ())) entity.remove();
                }
                var targetWorld = BukkitAdapter.adapt(world);
                BlockBounds bounds = instance.map().getBounds();
                return async(() -> {
                    // This may have waited for a worker after the main-thread checks above.
                    if (!instance.isReset(revision)) throw new IllegalStateException("Arena reset was cancelled.");
                    try (EditSession session = WorldEdit.getInstance().newEditSession(targetWorld)) {
                        Operations.complete(new ClipboardHolder(clipboard).createPaste(session)
                                .to(BlockVector3.at(bounds.minX(), bounds.minY(), bounds.minZ()))
                                .ignoreAirBlocks(false).copyEntities(true).copyBiomes(true).build());
                    }
                    return null;
                });
            });
        }).<Void>thenApply(ignored -> {
            requireReset(instance, revision);
            instance.restored(revision);
            return null;
        }).whenComplete((ignored, failure) -> { if (failure != null) instance.failed(revision); });
    }

    private void requireReset(ArenaInstance instance, long revision) {
        if (closed || !instance.isReset(revision) || !instances(instance.map().getId()).contains(instance)) {
            throw new IllegalStateException("Arena reset was cancelled.");
        }
    }

    private CompletableFuture<Clipboard> capture(ArenaMap map) {
        World source = Bukkit.getWorld(map.getWorldName());
        if (source == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Load the source world before saving this map."));
        var sourceWorld = BukkitAdapter.adapt(source);
        BlockBounds bounds = map.getBounds();
        String fileName = map.getId() + "-" + UUID.randomUUID() + ".schem";
        return enqueue(() -> chunks.run(source, bounds, () -> async(() -> {
            CuboidRegion region = new CuboidRegion(sourceWorld,
                    BlockVector3.at(bounds.minX(), bounds.minY(), bounds.minZ()),
                    BlockVector3.at(bounds.maxX(), bounds.maxY(), bounds.maxZ()));
            Clipboard clipboard = new BlockArrayClipboard(region);
            clipboard.setOrigin(region.getMinimumPoint());
            try (EditSession session = WorldEdit.getInstance().newEditSession(sourceWorld)) {
                ForwardExtentCopy copy = new ForwardExtentCopy(session, region, clipboard, region.getMinimumPoint());
                copy.setCopyingEntities(true);
                copy.setCopyingBiomes(true);
                Operations.complete(copy);
            }
            for (var entity : new ArrayList<>(clipboard.getEntities())) {
                if (entity.getState() != null && entity.getState().getType().getId().equals("minecraft:player")) {
                    clipboard.removeEntity(entity);
                }
            }
            Path path = schematicPath(fileName);
            Files.createDirectories(path.getParent());
            try (var output = Files.newOutputStream(path);
                 var writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(output)) {
                writer.write(clipboard);
            }
            return clipboard;
        }))).thenApply(clipboard -> { map.setSchematic(fileName); return clipboard; });
    }

    private Path schematicPath(String name) {
        if (!name.matches("[a-zA-Z0-9_-]+\\.schem")) throw new IllegalArgumentException("Invalid schematic filename.");
        return plugin.getDataFolder().toPath().resolve("maps").resolve(name);
    }
    private void validateBounds(ArenaMap map) {
        BlockBounds b = map.getBounds();
        if (b == null) throw new IllegalArgumentException("Select the map region first.");
        if (b.volume() > plugin.getConfig().getLong("arenas.max-template-blocks", 100_000_000L)) {
            throw new IllegalArgumentException("The selection exceeds max-template-blocks in config.yml.");
        }
        if (b.width() > spacing - 64 || b.length() > spacing - 64) {
            throw new IllegalArgumentException("The selection is too wide for the configured arena spacing.");
        }
        if (b.minY() < world.getMinHeight() || b.maxY() >= world.getMaxHeight()) {
            throw new IllegalArgumentException("Keep the selection within the world's build height.");
        }
    }
    private void validateSpawn(ArenaMap map, SpawnPoint spawn) {
        if (!map.getBounds().contains(spawn.x(), spawn.y(), spawn.z())) {
            throw new IllegalArgumentException("Set the spawns inside the selected map region.");
        }
    }

    private <T> CompletableFuture<T> enqueue(Supplier<CompletableFuture<T>> operation) {
        CompletableFuture<T> next = work.handle((ignored, failure) -> null).thenCompose(ignored -> {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Arena preparation stopped."));
            return operation.get();
        });
        work = next.handle((ignored, failure) -> null);
        return next;
    }
    @FunctionalInterface private interface Job<T> { T run() throws Exception; }
    private <T> CompletableFuture<T> async(Job<T> job) {
        CompletableFuture<T> future = new CompletableFuture<>();
        pending.add(future);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                T value = job.run();
                completeOnMain(future, value, null);
            } catch (Throwable failure) {
                completeOnMain(future, null, failure);
            }
        });
        return future;
    }
    private <T> void completeOnMain(CompletableFuture<T> future, T value, Throwable failure) {
        if (!plugin.isEnabled()) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pending.remove(future);
            if (closed) return;
            if (failure == null) future.complete(value);
            else future.completeExceptionally(failure);
        });
    }
    public void shutdown() {
        closed = true;
        chunks.close();
        pools.values().forEach(pool -> pool.forEach(ArenaInstance::failed));
        for (CompletableFuture<?> future : new ArrayList<>(pending)) future.cancel(false);
        pending.clear();
        // Active FAWE work may still reference a clipboard; do not close it during a paste.
    }
}
