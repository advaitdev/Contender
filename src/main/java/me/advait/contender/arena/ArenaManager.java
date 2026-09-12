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
import org.bukkit.scheduler.BukkitTask;

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
    private final Map<String, Boolean> templateCacheEligibility = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> editing = new HashSet<>();
    private final Set<CompletableFuture<?>> pending = new HashSet<>();
    private final Map<String, CompletableFuture<Void>> preparing = new HashMap<>();
    private final Map<ArenaInstance, CompletableFuture<Void>> restoring = new IdentityHashMap<>();
    private final Map<Object, Retry> retries = new HashMap<>();
    private final Map<String, String> failures = new HashMap<>();
    private final Set<String> scheduledPreparation = new HashSet<>();
    private record Retry(int attempts, long afterTick) { }
    private long maintenanceTick;
    private BukkitTask maintenance;
    private CompletableFuture<Void> work = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> templateWork = CompletableFuture.completedFuture(null);
    private final Map<Object, Progress> progress = new LinkedHashMap<>();
    private static final class Progress {
        final String mapId;
        String stage;
        long since;
        long lastReport;
        boolean waiting;
        volatile Thread worker;
        Progress(String mapId, String stage) { this.mapId = mapId; phase(stage, true); }
        void phase(String stage, boolean waiting) {
            this.stage = stage;
            this.waiting = waiting;
            since = lastReport = System.nanoTime();
        }
        String description() {
            long seconds = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - since);
            return stage + " (" + seconds + "s).";
        }
    }
    private World world;
    private ArenaReadyCache readyCache;
    private boolean closed;
    private int spacing;

    public ArenaManager(Contender plugin, MapManager maps) {
        this.plugin = plugin;
        this.maps = maps;
        this.chunks = new ArenaChunks(plugin);
    }

    public void initialize() {
        if (readyCache == null) {
            readyCache = new ArenaReadyCache(plugin.getDataFolder().toPath());
            try { readyCache.consume(); }
            catch (IOException failure) {
                // An old record must not survive a running server that can change its copies.
                try { readyCache.invalidate(); }
                catch (IOException blocked) {
                    blocked.addSuppressed(failure);
                    throw new IllegalStateException("Could not invalidate arena-ready.yml before loading arenas.", blocked);
                }
                plugin.getLogger().log(Level.WARNING, "Could not read the saved arena readiness; copies will rebuild.", failure);
            }
        }
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
        if (maintenance != null) maintenance.cancel();
        maintenance = plugin.getServer().getScheduler().runTaskTimer(plugin, this::maintain, 100L, 100L);
        maintain();
    }

    public World getWorld() { return world; }
    public boolean isArenaWorld(World candidate) { return world != null && world.equals(candidate); }
    public boolean isEditing(String mapId) { return editing.contains(mapId); }
    public boolean hasPendingWork() { return !work.isDone() || !templateWork.isDone() || !editing.isEmpty() || pools.keySet().stream().anyMatch(this::isBusy); }
    public void reloadTemplates() {
        if (hasPendingWork()) throw new IllegalStateException("Wait for matches and arena preparation to finish.");
        if (readyCache != null) readyCache.forget();
        pools.clear();
        retries.clear();
        failures.clear();
        clipboards.values().forEach(Clipboard::close);
        clipboards.clear();
        templateCacheEligibility.clear();
        initialize();
    }
    public List<ArenaInstance> instances(String mapId) { return List.copyOf(pools.getOrDefault(mapId, List.of())); }
    public int available(String mapId) {
        return (int) instances(mapId).stream().filter(a -> a.status() == ArenaInstance.Status.READY).count();
    }
    public boolean isBusy(String mapId) {
        return isEditing(mapId) || preparing.containsKey(mapId) || restoring.keySet().stream().anyMatch(a -> a.map().getId().equals(mapId))
                || instances(mapId).stream().anyMatch(a -> a.isReserved()
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
    /** Releases a cancelled game's copy immediately; maintenance resets it before reuse. */
    public void abandon(ArenaLease lease) {
        if (lease == null || !lease.instance().owns(lease)) return;
        lease.instance().failed();
        lease.instance().release(lease);
    }
    /** Prevents new arrivals while the slot is queued or being pasted; occupants can still leave. */
    public boolean isPreparingEntry(Location from, Location to) {
        if (to == null || !isArenaWorld(to.getWorld())) return false;
        for (List<ArenaInstance> pool : pools.values()) {
            for (ArenaInstance instance : pool) {
                if (instance.status() != ArenaInstance.Status.PREPARING && instance.status() != ArenaInstance.Status.RESETTING
                        && !restoring.containsKey(instance)) continue;
                if (!instance.cell().containsColumn(to.getX(), to.getZ())) continue;
                return from == null || !isArenaWorld(from.getWorld())
                        || !instance.cell().containsColumn(from.getX(), from.getZ());
            }
        }
        return false;
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
        forgetReadyCopies(map);
        pools.remove(map.getId());
        maps.save(map);
        prepareSoon(map);
    }

    public void configure(ArenaMap map, String name, int copies) {
        requireEditable(map);
        if (name.isBlank() || name.length() > 64) throw new IllegalArgumentException("Choose a map name between 1 and 64 characters.");
        map.setCopies(copies);
        map.setDisplayName(name.strip());
        forgetReadyCopies(map);
        pools.remove(map.getId());
        maps.save(map);
        prepareSoon(map);
    }

    public CompletableFuture<Void> saveBlocks(ArenaMap map) {
        requireEditable(map);
        forgetReadyCopies(map);
        editing.add(map.getId());
        pools.remove(map.getId());
        return capture(map).thenAccept(clipboard -> {
            Clipboard old = clipboards.put(map.getId(), clipboard);
            if (old != null) old.close();
            maps.save(map);
        }).whenComplete((ignored, failure) -> {
            editing.remove(map.getId());
            if (failure == null) prepareSoon(map);
        });
    }

    private void requireEditable(ArenaMap map) {
        if (isBusy(map.getId())) {
            if (instances(map.getId()).stream().anyMatch(ArenaInstance::isReserved)) {
                throw new IllegalStateException("Finish this map's matches before editing it.");
            }
            throw new IllegalStateException("This map is still preparing. You can edit it once preparation finishes.");
        }
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

    /** Ensures all usable slots are prepared, without disturbing copies already in use. */
    public CompletableFuture<Void> prepare(ArenaMap map) { return prepare(map, false); }

    private CompletableFuture<Void> prepare(ArenaMap map, boolean automatic) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Arena preparation stopped."));
        CompletableFuture<Void> running = preparing.get(map.getId());
        if (running != null) return running;
        try {
            if (isEditing(map.getId())) throw new IllegalStateException("This map is being saved. Its copies will prepare automatically.");
            validateBounds(map);
            if (!map.isComplete()) throw new IllegalArgumentException("Set both team spawns first.");
            validateSpawn(map, map.getTeam1Point());
            validateSpawn(map, map.getTeam2Point());
            if (map.getSpectatorPoint() != null) validateSpawn(map, map.getSpectatorPoint());
            List<ArenaInstance> pool = pools.get(map.getId());
            List<ArenaInstance> reusable = new ArrayList<>();
            if (pool == null) {
                int first = maps.allocateSlots(map);
                pool = new ArrayList<>();
                for (int i = 0; i < map.getCopies(); i++) {
                    pool.add(ArenaLayout.place(map, world.getName(), first + i, spacing, world.getMinHeight(), world.getMaxHeight()));
                }
                pools.put(map.getId(), pool);
                if (readyCache != null) {
                    for (ArenaInstance instance : pool) {
                        try {
                            if (empty(instance) && readyCache.reusable(map, instance, world, spacing)) reusable.add(instance);
                        } catch (IOException failure) {
                            plugin.getLogger().log(Level.WARNING, "Could not check saved arena copy " + instance.slot() + "; it will rebuild.", failure);
                        }
                    }
                    forgetReadyCopies(map);
                }
            }
            List<ArenaInstance> candidates = pool.stream().filter(instance -> !instance.isReserved())
                    .filter(instance -> restoring.containsKey(instance)
                            || needsRepair(instance) && empty(instance) && (!automatic || retryDue(instance))).toList();
            if (candidates.isEmpty()) return CompletableFuture.completedFuture(null);
            CompletableFuture<Void> result = new CompletableFuture<>();
            preparing.put(map.getId(), result);
            loadTemplate(map).thenCompose(clipboard -> {
                if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Arena preparation stopped."));
                clipboards.put(map.getId(), clipboard);
                // Cached copies still need a loaded template before any lease can later reset them.
                if (cacheable(map)) {
                    for (ArenaInstance instance : reusable) instance.reuseSavedCopy();
                    if (!reusable.isEmpty()) plugin.getLogger().info("Reused " + reusable.size() + " saved arena copies for " + map.getId() + ".");
                }
                CompletableFuture<Void> attempts = CompletableFuture.completedFuture(null);
                for (ArenaInstance instance : candidates) {
                    // Queue one copy per map at a time, allowing other maps to make progress.
                    // A failure ends this attempt immediately instead of hiding behind the rest of the pool.
                    attempts = attempts.thenCompose(ignored -> {
                        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Arena preparation stopped."));
                        CompletableFuture<Void> current = restoring.get(instance);
                        if (current != null) return current;
                        if (!instance.isReserved() && needsRepair(instance) && empty(instance)) return restore(instance, clipboard, true);
                        return CompletableFuture.completedFuture(null);
                    });
                }
                return attempts;
            }).whenComplete((ignored, failure) -> {
                preparing.remove(map.getId(), result);
                if (failure == null) {
                    retries.remove(map.getId());
                    failures.remove(map.getId());
                    result.complete(null);
                } else {
                    instances(map.getId()).stream().filter(this::needsRepair)
                            .filter(instance -> !restoring.containsKey(instance)).forEach(ArenaInstance::failed);
                    retryLater(map.getId());
                    failures.put(map.getId(), failureMessage(failure));
                    result.completeExceptionally(failure);
                }
            });
            return result;
        } catch (Exception failure) {
            retryLater(map.getId());
            failures.put(map.getId(), failureMessage(failure));
            CompletableFuture<Void> interrupted = preparing.remove(map.getId());
            if (interrupted != null) {
                instances(map.getId()).stream().filter(this::needsRepair).forEach(ArenaInstance::failed);
                interrupted.completeExceptionally(failure);
                return interrupted;
            }
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletableFuture<Clipboard> loadTemplate(ArenaMap map) {
        Clipboard cached = clipboards.get(map.getId());
        if (cached != null) return CompletableFuture.completedFuture(cached);
        if (map.getSchematic() == null) return capture(map).thenApply(clipboard -> { maps.save(map); return clipboard; });
        Progress status = new Progress(map.getId(), "Waiting to read the saved template");
        progress.put(map.getId(), status);
        return enqueueTemplate(() -> {
            status.phase("Reading the saved template", false);
            return async(status, () -> {
                try (var input = Files.newInputStream(schematicPath(map.getSchematic()));
                     var reader = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getReader(input)) {
                    Clipboard clipboard = reader.read();
                    classifyTemplate(map.getSchematic(), clipboard);
                    return clipboard;
                }
            });
        }).whenComplete((ignored, failure) -> progress.remove(map.getId(), status));
    }

    private void forgetReadyCopies(ArenaMap map) {
        if (readyCache != null) readyCache.forget(map.getId());
    }

    private void classifyTemplate(String revision, Clipboard clipboard) {
        if (revision == null || templateCacheEligibility.containsKey(revision)) return;
        boolean cacheable = false;
        try { cacheable = ArenaTemplateSafety.cacheable(clipboard); }
        catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not check template " + revision + " for restart reuse; its copies will rebuild.", failure);
        }
        templateCacheEligibility.put(revision, cacheable);
    }

    private boolean cacheable(ArenaMap map) {
        return map.getSchematic() != null && Boolean.TRUE.equals(templateCacheEligibility.get(map.getSchematic()));
    }

    private boolean needsRepair(ArenaInstance instance) {
        return instance.status() == ArenaInstance.Status.FAILED || instance.status() == ArenaInstance.Status.PREPARING;
    }

    private boolean empty(ArenaInstance instance) {
        try { requireEmpty(instance); return true; }
        catch (IllegalStateException occupied) { return false; }
    }

    /** Runs on the server thread; retries are bounded to once a minute after repeated failures. */
    void maintain() {
        if (closed) return;
        maintenanceTick += 100;
        reportSlowOperations();
        for (ArenaMap map : maps.getMaps()) {
            if (!map.isComplete() || isEditing(map.getId()) || preparing.containsKey(map.getId()) || !retryDue(map.getId())) continue;
            List<ArenaInstance> pool = pools.get(map.getId());
            if (pool != null && pool.stream().noneMatch(instance -> !instance.isReserved()
                    && !restoring.containsKey(instance) && needsRepair(instance) && empty(instance) && retryDue(instance))) continue;
            prepare(map, true).exceptionally(failure -> {
                if (!closed) plugin.getLogger().log(Level.WARNING,
                        "Arena preparation for " + map.getId() + " will retry automatically: " + failureMessage(failure));
                return null;
            });
        }
    }

    private void prepareSoon(ArenaMap map) {
        if (closed || !map.isComplete() || !scheduledPreparation.add(map.getId())) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            scheduledPreparation.remove(map.getId());
            if (!closed && maps.getMap(map.getId()) == map) {
                retries.remove(map.getId());
                prepare(map).exceptionally(failure -> {
                    if (!closed) plugin.getLogger().log(Level.WARNING,
                            "Arena preparation for " + map.getId() + " will retry automatically: " + failureMessage(failure));
                    return null;
                });
            }
        });
    }

    private boolean retryDue(Object key) {
        Retry retry = retries.get(key);
        return retry == null || retry.afterTick() <= maintenanceTick;
    }

    private void retryLater(Object key) {
        Retry previous = retries.get(key);
        int attempt = previous == null ? 1 : Math.min(previous.attempts() + 1, 5);
        retries.put(key, new Retry(attempt, maintenanceTick + Math.min(1200L, 100L << (attempt - 1))));
    }

    private static String failureMessage(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    /** A short status suitable for dialogs and start errors. */
    public String readiness(String mapId) {
        ArenaMap map = maps.getMap(mapId);
        if (map == null) return "This map is no longer saved.";
        if (!map.isComplete()) return "Set both team spawns to prepare this map.";
        int ready = available(mapId);
        String count = ready + " of " + map.getCopies() + " arena copies ready.";
        if (ready == map.getCopies()) return count;
        String operation = operationStatus(mapId);
        if (progress.values().stream().anyMatch(status -> status.mapId.equals(mapId))) return count + " " + operation;
        if (isEditing(mapId)) return count + " Saving the map.";
        if (preparing.containsKey(mapId) || instances(mapId).stream().anyMatch(restoring::containsKey)) {
            return count + " Preparing the remaining copies automatically.";
        }
        if (instances(mapId).stream().anyMatch(instance -> instance.isReserved() || !empty(instance))) {
            return count + " Other copies will reset when their players leave.";
        }
        String failure = failures.get(mapId);
        if (failure != null) return count + " Retrying automatically: " + failure;
        return count + " The remaining copies will prepare automatically.";
    }

    public String operationStatus(String mapId) {
        Progress current = progress.values().stream().filter(status -> status.mapId.equals(mapId))
                .min(Comparator.comparing(status -> status.waiting)).orElse(null);
        String failure = failures.get(mapId);
        String status = current != null ? current.description()
                : failure != null ? "Waiting to retry automatically." : isEditing(mapId) ? "Saving the map." : "No arena work is running for this map.";
        return failure == null ? status : status + " Last attempt: " + failure;
    }

    private void reportSlowOperations() {
        long now = System.nanoTime();
        for (Progress status : progress.values()) {
            if (now - status.lastReport < java.util.concurrent.TimeUnit.SECONDS.toNanos(60)) continue;
            status.lastReport = now;
            plugin.getLogger().info("Arena " + status.mapId + ": " + status.description());
            Thread worker = status.worker;
            if (worker != null) {
                String trace = Arrays.stream(worker.getStackTrace()).limit(12).map(frame -> "    at " + frame)
                        .collect(java.util.stream.Collectors.joining("\n"));
                plugin.getLogger().info("Arena worker for " + status.mapId + " (" + worker.getState() + "):\n" + trace);
            }
        }
    }

    public CompletableFuture<Void> reset(ArenaLease lease) {
        if (closed || lease == null || !lease.instance().owns(lease)) return CompletableFuture.failedFuture(new IllegalStateException("Arena reservation expired."));
        if (lease.instance().status() == ArenaInstance.Status.FAILED) return CompletableFuture.failedFuture(
                new IllegalStateException("This arena copy is waiting for an automatic reset."));
        Clipboard clipboard = clipboards.get(lease.instance().map().getId());
        if (clipboard == null) return CompletableFuture.failedFuture(new IllegalStateException("Map template is not loaded."));
        return restore(lease.instance(), clipboard, false);
    }

    private CompletableFuture<Void> restore(ArenaInstance instance, Clipboard clipboard, boolean repair) {
        CompletableFuture<Void> active = restoring.get(instance);
        if (active != null) return active;
        if (repair && instance.status() == ArenaInstance.Status.FAILED) instance.beginRepair();
        long revision = instance.beginReset();
        CompletableFuture<Void> result = new CompletableFuture<>();
        restoring.put(instance, result);
        List<ArenaInstance> pool = instances(instance.map().getId());
        String copy = "copy " + (pool.indexOf(instance) + 1) + " of " + pool.size();
        Progress status = new Progress(instance.map().getId(), "Waiting to prepare " + copy);
        progress.put(instance, status);
        enqueue(() -> {
            requireReset(instance, revision);
            status.phase("Loading chunks and entities for " + copy, false);
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
                status.phase("Pasting " + copy + " (" + String.format(Locale.ROOT, "%,d", bounds.volume()) + " blocks)", false);
                return async(status, () -> {
                    // This may have waited for a worker after the main-thread checks above.
                    if (!instance.isReset(revision)) throw new IllegalStateException("Arena reset was cancelled.");
                    // Saved schematics provide rollback; FAWE undo history duplicates every pasted block.
                    try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(targetWorld).changeSetNull().build()) {
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
        }).whenComplete((ignored, failure) -> {
            progress.remove(instance, status);
            restoring.remove(instance, result);
            if (failure != null) {
                instance.failed(revision);
                retryLater(instance);
                failures.put(instance.map().getId(), failureMessage(failure));
                result.completeExceptionally(failure);
            } else {
                retries.remove(instance);
                result.complete(null);
            }
        });
        return result;
    }

    private void requireReset(ArenaInstance instance, long revision) {
        if (closed || !instance.isReset(revision) || !instances(instance.map().getId()).contains(instance)) {
            throw new IllegalStateException("Arena reset was cancelled.");
        }
    }

    private CompletableFuture<Clipboard> capture(ArenaMap map) {
        World source;
        try {
            source = Bukkit.getWorld(map.getWorldName());
            if (source == null) source = plugin.getManagedWorlds().loadExisting(map.getWorldName());
        } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        if (source == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Could not load the source world."));
        final World loadedSource = source;
        BlockBounds bounds = map.getBounds();
        String fileName = map.getId() + "-" + UUID.randomUUID() + ".schem";
        Progress status = new Progress(map.getId(), "Waiting to save the map selection");
        progress.put(map.getId(), status);
        return enqueueTemplate(() -> {
            status.phase("Loading the source map's chunks and entities", false);
            return chunks.run(loadedSource, bounds, () -> {
                var sourceWorld = BukkitAdapter.adapt(loadedSource);
                status.phase("Saving " + String.format(Locale.ROOT, "%,d", bounds.volume()) + " selected blocks and entities", false);
                return async(status, () -> {
                    CuboidRegion region = new CuboidRegion(sourceWorld,
                            BlockVector3.at(bounds.minX(), bounds.minY(), bounds.minZ()),
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
                        if (entity.getState() != null && entity.getState().getType().getId().equals("minecraft:player")) {
                            clipboard.removeEntity(entity);
                        }
                    }
                    classifyTemplate(fileName, clipboard);
                    Path path = schematicPath(fileName);
                    Files.createDirectories(path.getParent());
                    try (var output = Files.newOutputStream(path);
                         var writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(output)) {
                        writer.write(clipboard);
                    }
                    return clipboard;
                });
            });
        }).thenApply(clipboard -> { map.setSchematic(fileName); return clipboard; })
                .whenComplete((ignored, failure) -> progress.remove(map.getId(), status));
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
        return enqueue(operation, false);
    }
    private <T> CompletableFuture<T> enqueueTemplate(Supplier<CompletableFuture<T>> operation) {
        return enqueue(operation, true);
    }
    private <T> CompletableFuture<T> enqueue(Supplier<CompletableFuture<T>> operation, boolean template) {
        CompletableFuture<Void> previous = template ? templateWork : work;
        CompletableFuture<Void> tail = new CompletableFuture<>();
        // Publish the new tail before running code that may complete inline and enqueue more work.
        if (template) templateWork = tail;
        else work = tail;
        CompletableFuture<T> next = previous.handle((ignored, failure) -> null).thenCompose(ignored -> {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Arena preparation stopped."));
            return operation.get();
        });
        next.whenComplete((ignored, failure) -> tail.complete(null));
        return next;
    }
    @FunctionalInterface private interface Job<T> { T run() throws Exception; }
    private <T> CompletableFuture<T> async(Progress progress, Job<T> job) {
        CompletableFuture<T> future = new CompletableFuture<>();
        pending.add(future);
        try { plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            progress.worker = Thread.currentThread();
            try {
                T value = job.run();
                completeOnMain(future, value, null);
            } catch (Throwable failure) {
                completeOnMain(future, null, failure);
            } finally { progress.worker = null; }
        }); } catch (RuntimeException failure) {
            pending.remove(future);
            future.completeExceptionally(failure);
        }
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
        if (closed) return;
        closed = true;
        if (maintenance != null) maintenance.cancel();
        scheduledPreparation.clear();
        chunks.close();
        saveReadyCopies();
        pools.values().forEach(pool -> pool.forEach(ArenaInstance::failed));
        for (CompletableFuture<?> future : new ArrayList<>(pending)) future.cancel(false);
        pending.clear();
        // Active FAWE work may still reference a clipboard; do not close it during a paste.
    }

    private void saveReadyCopies() {
        if (readyCache == null || world == null) return;
        if (!plugin.getServer().isStopping()) {
            try { readyCache.invalidate(); }
            catch (IOException failure) { plugin.getLogger().log(Level.WARNING, "Could not invalidate arena readiness after plugin disable.", failure); }
            return;
        }
        try {
            List<ArenaReadyCache.Entry> saved = new ArrayList<>();
            for (ArenaMap map : maps.getMaps()) {
                if (isEditing(map.getId()) || !cacheable(map)) continue;
                for (ArenaInstance instance : instances(map.getId())) {
                    if (instance.canCache() && !restoring.containsKey(instance) && empty(instance)) {
                        try { saved.add(readyCache.entry(map, instance, world, spacing)); }
                        catch (IOException failure) {
                            plugin.getLogger().log(Level.WARNING, "Could not cache arena copy " + instance.slot() + "; it will rebuild next startup.", failure);
                        }
                    }
                }
            }
            // Flush chunk/entity writes before publishing a cache that will skip their next paste.
            // Active and partially pasted copies were excluded above and always rebuild after a restart.
            if (!saved.isEmpty()) world.save(true);
            readyCache.write(saved);
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not save arena readiness; copies will rebuild next startup.", failure);
        }
    }
}
