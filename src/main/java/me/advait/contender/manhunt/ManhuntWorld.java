package me.advait.contender.manhunt;

import me.advait.contender.Contender;
import me.advait.contender.util.YamlStorage;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Creates only uniquely named, owned End worlds. Used worlds are never silently reused or deleted. */
final class ManhuntWorld {
    enum State { EMPTY, PREPARING, READY, USED, FAILED }
    private static final NamespacedKey FROZEN_GRAVITY = new NamespacedKey("contender", "manhunt_frozen_gravity");
    private static final NamespacedKey FROZEN_AI = new NamespacedKey("contender", "manhunt_frozen_ai");
    private record Frozen(Entity entity, Location location, boolean gravity, Boolean ai) { }
    private final Contender plugin;
    private final File file;
    private final Set<Chunk> tickets = new HashSet<>();
    private final Map<UUID, Frozen> frozen = new HashMap<>();
    private final Set<CompletableFuture<Void>> entityLoads = new HashSet<>();
    private final List<List<Location>> pillars = new ArrayList<>();
    private State state = State.EMPTY;
    private String name;
    private World world;
    private boolean freezing;
    private boolean loadedPreparedWorld;
    private long generation;

    ManhuntWorld(Contender plugin) { this.plugin = plugin; file = new File(plugin.getDataFolder(), "manhunt-world.yml"); }
    String name() { return name; }
    World world() { return world; }
    State state() { return state; }
    int pillarCount() { return pillars.size(); }
    boolean contains(Location location) { return world != null && world.equals(location.getWorld()); }
    boolean frozen() { return freezing; }
    boolean ready() { return state == State.READY && world != null && !pillars.isEmpty(); }
    private void save() {
        var yaml = new YamlConfiguration(); yaml.set("world", name); yaml.set("state", state.name());
        if (world != null) yaml.set("folder", world.getWorldFolder().getAbsolutePath());
        YamlStorage.save(yaml, file);
    }
    CompletableFuture<Void> reload(BooleanSupplier valid) {
        var yaml = YamlConfiguration.loadConfiguration(file);
        String saved = yaml.getString("world");
        if (saved == null || !saved.matches("contender_manhunt_[a-f0-9]{32}")) return CompletableFuture.completedFuture(null);
        name = saved;
        state = State.valueOf(yaml.getString("state", "EMPTY"));
        if (state == State.PREPARING) state = State.FAILED;
        if (state != State.READY) return CompletableFuture.completedFuture(null);
        String folder = yaml.getString("folder");
        if (folder == null || !new File(folder).isDirectory()) { state = State.FAILED; save(); return CompletableFuture.completedFuture(null); }
        loadedPreparedWorld = true;
        return load(valid);
    }
    CompletableFuture<Void> prepare(BooleanSupplier valid) {
        if (state == State.PREPARING) throw new IllegalStateException("The End is still being prepared.");
        if (world != null && !world.getPlayers().isEmpty()) throw new IllegalStateException("Everyone must leave the previous End world first.");
        close();
        loadedPreparedWorld = false;
        name = "contender_manhunt_" + UUID.randomUUID().toString().replace("-", "");
        return load(valid);
    }
    private CompletableFuture<Void> load(BooleanSupplier valid) {
        long expectedGeneration = ++generation;
        BooleanSupplier current = () -> generation == expectedGeneration && valid.getAsBoolean();
        state = State.PREPARING; save();
        world = new WorldCreator(name).environment(World.Environment.THE_END).generateStructures(true).createWorld();
        if (world == null) { state = State.FAILED; save(); throw new IllegalStateException("The End world could not be created."); }
        world.setGameRule(GameRules.LOCATOR_BAR, false); world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        world.setGameRule(GameRules.ADVANCE_TIME, false); world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.RANDOM_TICK_SPEED, 0); world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true); world.setGameRule(GameRules.KEEP_INVENTORY, true);
        world.setDifficulty(Difficulty.NORMAL); world.setPVP(true); world.setTime(6000);
        freeze();
        World preparingWorld = world;
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        // A small queue avoids issuing hundreds of chunk generation requests at once.
        List<int[]> coordinates = new ArrayList<>();
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) coordinates.add(new int[]{x, z});
        for (int index = 0; index < coordinates.size(); index += 8) {
            var batch = coordinates.subList(index, Math.min(index + 8, coordinates.size()));
            chain = chain.thenCompose(ignored -> {
                if (!current.getAsBoolean()) return CompletableFuture.failedFuture(new IllegalStateException("End preparation stopped."));
                var loads = batch.stream().map(c -> preparingWorld.getChunkAtAsync(c[0], c[1], true).thenAccept(chunk -> {
                    if (current.getAsBoolean()) { chunk.addPluginChunkTicket(plugin); tickets.add(chunk); }
                })).toArray(CompletableFuture[]::new);
                return CompletableFuture.allOf(loads);
            });
        }
        return chain.thenCompose(ignored -> awaitEntities(current)).thenRun(() -> {
            if (!current.getAsBoolean()) throw new IllegalStateException("End preparation stopped.");
            findPillars();
            if (pillars.isEmpty()) throw new IllegalStateException("No obsidian pillars were found in the End.");
            // This dragon gives preparation a real, frozen target even before a player activates the native battle.
            if (world.getEntitiesByClass(EnderDragon.class).isEmpty()) {
                EnderDragon dragon = world.spawn(new Location(world, 0, 110, 0), EnderDragon.class);
                dragon.setPhase(EnderDragon.Phase.CIRCLING);
            }
            freezeTick(); world.save(); state = State.READY; save();
        }).whenComplete((ignored, failure) -> {
            if (failure != null && generation == expectedGeneration) {
                state = State.FAILED;
                try { save(); } finally { releaseTickets(); }
            }
        });
    }
    private CompletableFuture<Void> awaitEntities(BooleanSupplier valid) {
        if (!valid.getAsBoolean()) return CompletableFuture.failedFuture(new IllegalStateException("End preparation stopped."));
        if (tickets.stream().allMatch(Chunk::isEntitiesLoaded)) return CompletableFuture.completedFuture(null);
        var ready = new CompletableFuture<Void>();
        entityLoads.add(ready);
        int[] waited = {0};
        // Block chunk futures can finish before saved entities arrive. Do not spawn a second dragon then.
        var task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!valid.getAsBoolean()) ready.completeExceptionally(new IllegalStateException("End preparation stopped."));
            else if (tickets.stream().allMatch(Chunk::isEntitiesLoaded)) ready.complete(null);
            else if (++waited[0] >= 1200) ready.completeExceptionally(new IllegalStateException("The End's entities took too long to load. Prepare the End again."));
        }, 1, 1);
        ready.whenComplete((ignored, failure) -> { task.cancel(); entityLoads.remove(ready); });
        return ready;
    }
    void use() {
        if (!ready()) throw new IllegalStateException("Prepare a fresh End world in Setup Tools first.");
        state = State.USED; save();
    }
    Location spawn(int index) {
        if (pillars.isEmpty()) throw new IllegalStateException("The End has no prepared spawns.");
        List<Location> positions = pillars.get(Math.floorMod(index, pillars.size()));
        return positions.get(Math.floorMod(index / pillars.size(), positions.size())).clone();
    }
    private void findPillars() {
        pillars.clear();
        // Vanilla End pillars form a ring around the origin. Their heights depend on the seed.
        for (int i = 0; i < 10; i++) {
            double angle = 2 * (-Math.PI + Math.PI * i / 10);
            int centerX = (int) Math.floor(42 * Math.cos(angle));
            int centerZ = (int) Math.floor(42 * Math.sin(angle));
            List<Location> positions = new ArrayList<>();
            for (int[] offset : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {1, 1}}) {
                int x = centerX + offset[0], z = centerZ + offset[1];
                for (int y = Math.min(world.getMaxHeight() - 4, 160); y >= 65; y--) {
                    if (world.getBlockAt(x, y, z).getType() != Material.OBSIDIAN) continue;
                    var feet = world.getBlockAt(x, y + 1, z); var head = world.getBlockAt(x, y + 2, z);
                    // Caged pillars can need a two-block opening beside the crystal.
                    if (feet.getType() == Material.IRON_BARS || feet.getType() == Material.FIRE) feet.setType(Material.AIR, false);
                    if (head.getType() == Material.IRON_BARS || head.getType() == Material.FIRE) head.setType(Material.AIR, false);
                    if (feet.isPassable() && head.isPassable()) {
                        Location found = new Location(world, x + .5, y + 1.02, z + .5);
                        found.setDirection(new Vector(-found.getX(), 0, -found.getZ()));
                        positions.add(found);
                    }
                    break;
                }
            }
            if (!positions.isEmpty()) pillars.add(List.copyOf(positions));
        }
    }
    void freeze() { freezing = true; freezeTick(); }
    void freezeEntity(Entity entity) {
        if (!freezing || entity instanceof Player || frozen.containsKey(entity.getUniqueId())) return;
        var data = entity.getPersistentDataContainer();
        Byte savedGravity = data.get(FROZEN_GRAVITY, PersistentDataType.BYTE);
        Byte savedAi = data.get(FROZEN_AI, PersistentDataType.BYTE);
        boolean gravity = savedGravity != null ? savedGravity != 0 : entity.hasGravity();
        Boolean ai = entity instanceof LivingEntity living ? savedAi != null ? savedAi != 0 : living.hasAI() : null;
        // Older prepared Ends saved disabled flags without recording their originals.
        // Only repair that exact state in a reloaded, owned End, leaving tagged values intact.
        if (loadedPreparedWorld && savedGravity == null && savedAi == null
                && entity instanceof Mob && !gravity && Boolean.FALSE.equals(ai)) {
            gravity = true;
            ai = true;
        }
        frozen.put(entity.getUniqueId(), new Frozen(entity, entity.getLocation(), gravity, ai));
        // Keep the originals with the entity, so a save or crash during preparation cannot lose them.
        data.set(FROZEN_GRAVITY, PersistentDataType.BYTE, (byte) (gravity ? 1 : 0));
        if (ai != null) data.set(FROZEN_AI, PersistentDataType.BYTE, (byte) (ai ? 1 : 0));
        entity.setVelocity(new Vector()); entity.setGravity(false);
        if (entity instanceof LivingEntity living) living.setAI(false);
    }
    void freezeTick() {
        if (!freezing || world == null) return;
        world.getEntities().forEach(this::freezeEntity);
        for (var saved : frozen.values()) if (saved.entity().isValid()) {
            saved.entity().setVelocity(new Vector());
            if (saved.entity().getLocation().distanceSquared(saved.location()) > .0001) saved.entity().teleport(saved.location());
        }
    }
    void thaw() {
        freezing = false;
        for (var saved : frozen.values()) if (saved.entity().isValid()) {
            saved.entity().setGravity(saved.gravity());
            if (saved.ai() != null && saved.entity() instanceof LivingEntity living) living.setAI(saved.ai());
            var data = saved.entity().getPersistentDataContainer();
            data.remove(FROZEN_GRAVITY);
            data.remove(FROZEN_AI);
            // Plugin-spawned dragons begin in HOVER, which never transitions on its own.
            if (saved.entity() instanceof EnderDragon dragon && !dragon.isDead()
                    && dragon.getPhase() == EnderDragon.Phase.HOVER) dragon.setPhase(EnderDragon.Phase.CIRCLING);
        }
        frozen.clear();
        loadedPreparedWorld = false;
        if (world != null) { world.setGameRule(GameRules.RANDOM_TICK_SPEED, 3); world.setGameRule(GameRules.SPAWN_MOBS, true); }
    }
    private void releaseTickets() {
        for (var pending : List.copyOf(entityLoads)) pending.completeExceptionally(new IllegalStateException("End preparation stopped."));
        tickets.forEach(c -> c.removePluginChunkTicket(plugin)); tickets.clear();
    }
    void cancelPreparation() {
        generation++;
        if (state != State.PREPARING) return;
        state = State.FAILED;
        try { save(); }
        finally { releaseTickets(); }
    }
    void close() {
        generation++;
        thaw(); releaseTickets(); pillars.clear();
        if (world != null && world.getPlayers().isEmpty()) Bukkit.unloadWorld(world, true);
        world = null;
    }
}
