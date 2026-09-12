package me.advait.contender.manhunt;

import me.advait.contender.Contender;
import io.papermc.paper.registry.RegistryAccess;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManhuntWorldFreezeTest {
    @TempDir Path folder;
    private org.mockito.MockedStatic<RegistryAccess> registries;
    private org.mockito.MockedStatic<io.papermc.paper.InternalAPIBridge> bridges;

    @BeforeEach void registries() {
        registries = mockStatic(RegistryAccess.class);
        bridges = mockStatic(io.papermc.paper.InternalAPIBridge.class);
        bridges.when(io.papermc.paper.InternalAPIBridge::get).thenReturn(mock(io.papermc.paper.InternalAPIBridge.class));
        registries.when(RegistryAccess::registryAccess).thenReturn(mock(RegistryAccess.class, RETURNS_MOCKS));
        var gameRules = Registry.GAME_RULE;
        Map<net.kyori.adventure.key.Key, GameRule<?>> rules = new HashMap<>();
        doAnswer(call -> rules.computeIfAbsent(call.getArgument(0), key -> mock(GameRule.class)))
                .when(gameRules).getOrThrow(any(net.kyori.adventure.key.Key.class));
    }

    @AfterEach void closeRegistries() { registries.close(); bridges.close(); }

    @Test void hoveringDragonCirclesWhenCountdownThawsAndRepeatedFreezeKeepsOriginalFlags() {
        var fixture = new Fixture();
        var dragon = fixture.mob(EnderDragon.class, true, true);
        when(dragon.getPhase()).thenReturn(EnderDragon.Phase.HOVER);
        fixture.entities.add(dragon);

        try (var creators = fixture.creators()) {
            var prepared = fixture.prepare();
            assertFalse(dragon.hasAI());
            assertFalse(dragon.hasGravity());
            prepared.freezeTick();
            prepared.freeze();
            prepared.thaw();

            assertTrue(dragon.hasAI());
            assertTrue(dragon.hasGravity());
            verify(dragon).setPhase(EnderDragon.Phase.CIRCLING);
            assertTrue(dragon.getPersistentDataContainer().isEmpty());
        }
    }

    @Test void frozenEntityFlagsSurviveRestartDuringPreparation() throws Exception {
        var fixture = new Fixture();
        var dragon = fixture.mob(EnderDragon.class, true, true);
        fixture.entities.add(dragon);
        try (var creators = fixture.creators()) {
            var old = fixture.prepare();
            assertFalse(dragon.hasAI());
            assertFalse(dragon.hasGravity());
            fixture.saveReady(old.name());

            var reloaded = new ManhuntWorld(fixture.plugin);
            reloaded.reload(() -> true);
            reloaded.thaw();

            assertTrue(dragon.hasAI());
            assertTrue(dragon.hasGravity());
            assertTrue(dragon.getPersistentDataContainer().isEmpty());
        }
    }

    @Test void legacyPreparedDragonWithNoSavedOriginalsRecoversAfterRestart() throws Exception {
        var fixture = new Fixture();
        var dragon = fixture.mob(EnderDragon.class, false, false);
        when(dragon.getPhase()).thenReturn(EnderDragon.Phase.HOVER);
        fixture.entities.add(dragon);
        fixture.saveReady("contender_manhunt_" + UUID.randomUUID().toString().replace("-", ""));

        try (var creators = fixture.creators()) {
            var reloaded = new ManhuntWorld(fixture.plugin);
            reloaded.reload(() -> true);
            reloaded.thaw();

            assertTrue(dragon.hasAI());
            assertTrue(dragon.hasGravity());
            verify(dragon).setPhase(EnderDragon.Phase.CIRCLING);
        }
    }

    @Test void recordedDisabledFlagsStayDisabledAfterRestart() throws Exception {
        var fixture = new Fixture();
        var mob = fixture.mob(Enderman.class, false, false);
        fixture.entities.add(mob);
        try (var creators = fixture.creators()) {
            var old = fixture.prepare();
            fixture.saveReady(old.name());

            var reloaded = new ManhuntWorld(fixture.plugin);
            reloaded.reload(() -> true);
            reloaded.thaw();

            assertFalse(mob.hasAI());
            assertFalse(mob.hasGravity());
            assertTrue(mob.getPersistentDataContainer().isEmpty());
        }
    }

    @Test void thawPreservesAnActiveOrDyingDragonPhase() {
        var fixture = new Fixture();
        var active = fixture.mob(EnderDragon.class, true, true);
        var dying = fixture.mob(EnderDragon.class, true, true);
        when(active.getPhase()).thenReturn(EnderDragon.Phase.STRAFING);
        when(dying.getPhase()).thenReturn(EnderDragon.Phase.DYING);
        fixture.entities.addAll(List.of(active, dying));

        try (var creators = fixture.creators()) {
            fixture.prepare().thaw();
            verify(active, never()).setPhase(any());
            verify(dying, never()).setPhase(any());
        }
    }

    @Test void freezingDoesNotTouchPlayers() {
        var fixture = new Fixture();
        var player = mock(Player.class);
        fixture.entities.add(player);

        try (var creators = fixture.creators()) {
            fixture.prepare().thaw();
            verify(player, never()).setAI(anyBoolean());
            verify(player, never()).setGravity(anyBoolean());
            verify(player, never()).getPersistentDataContainer();
        }
    }

    @Test void preparationWaitsForSavedEntitiesBeforeCheckingForADragon() {
        var fixture = new Fixture();
        fixture.completeChunkLoads();
        var dragon = fixture.mob(EnderDragon.class, true, true);
        when(fixture.world.getEntitiesByClass(EnderDragon.class)).thenAnswer(call -> fixture.entities.contains(dragon) ? List.of(dragon) : List.of());

        try (var creators = fixture.creators()) {
            var prepared = new ManhuntWorld(fixture.plugin);
            var future = prepared.prepare(() -> true);
            assertFalse(future.isDone());
            verify(fixture.world, never()).spawn(any(Location.class), eq(EnderDragon.class));
            fixture.entities.add(dragon);
            fixture.entitiesLoaded.set(true);
            fixture.entityPoll.run();

            assertTrue(future.isDone());
            assertFalse(future.isCompletedExceptionally());
            assertTrue(prepared.ready());
            verify(fixture.world, never()).spawn(any(Location.class), eq(EnderDragon.class));
            verify(fixture.pollTask).cancel();
            assertFalse(dragon.hasAI());
        }
    }

    @Test void newFallbackDragonIsGivenAnActiveFlyingPhase() {
        var fixture = new Fixture();
        fixture.completeChunkLoads();
        fixture.entitiesLoaded.set(true);
        var dragon = fixture.mob(EnderDragon.class, true, true);
        when(fixture.world.getEntitiesByClass(EnderDragon.class)).thenReturn(List.of());
        when(fixture.world.spawn(any(Location.class), eq(EnderDragon.class))).thenAnswer(call -> {
            fixture.entities.add(dragon);
            return dragon;
        });

        try (var creators = fixture.creators()) {
            var prepared = new ManhuntWorld(fixture.plugin);
            prepared.prepare(() -> true).join();

            assertTrue(prepared.ready());
            verify(dragon).setPhase(EnderDragon.Phase.CIRCLING);
            assertFalse(dragon.hasAI());
            prepared.thaw();
            assertTrue(dragon.hasAI());
        }
    }

    @Test void cancellationStopsWaitingForEntitiesAndCannotSpawnADragon() {
        var fixture = new Fixture();
        fixture.completeChunkLoads();

        try (var creators = fixture.creators()) {
            var prepared = new ManhuntWorld(fixture.plugin);
            var future = prepared.prepare(() -> true);
            prepared.cancelPreparation();
            fixture.entitiesLoaded.set(true);
            fixture.entityPoll.run();

            assertTrue(future.isCompletedExceptionally());
            assertEquals(ManhuntWorld.State.FAILED, prepared.state());
            verify(fixture.world, never()).spawn(any(Location.class), eq(EnderDragon.class));
            verify(fixture.pollTask).cancel();
            fixture.chunks.forEach(chunk -> verify(chunk).removePluginChunkTicket(fixture.plugin));
        }
    }

    @Test void entityLoadTimeoutFailsPreparationAndReleasesChunks() {
        var fixture = new Fixture();
        fixture.completeChunkLoads();

        try (var creators = fixture.creators()) {
            var prepared = new ManhuntWorld(fixture.plugin);
            var future = prepared.prepare(() -> true);
            for (int tick = 0; tick < 1200; tick++) fixture.entityPoll.run();

            assertTrue(future.isCompletedExceptionally());
            assertEquals(ManhuntWorld.State.FAILED, prepared.state());
            verify(fixture.world, never()).spawn(any(Location.class), eq(EnderDragon.class));
            verify(fixture.pollTask).cancel();
            fixture.chunks.forEach(chunk -> verify(chunk).removePluginChunkTicket(fixture.plugin));
        }
    }

    private final class Fixture {
        final Contender plugin = mock(Contender.class);
        final World world = mock(World.class);
        final List<Entity> entities = new ArrayList<>();
        final List<Chunk> chunks = new ArrayList<>();
        final AtomicBoolean entitiesLoaded = new AtomicBoolean();
        final org.bukkit.scheduler.BukkitTask pollTask = mock(org.bukkit.scheduler.BukkitTask.class);
        Runnable entityPoll;

        Fixture() {
            when(plugin.getDataFolder()).thenReturn(folder.toFile());
            when(world.getWorldFolder()).thenReturn(folder.toFile());
            when(world.getEntities()).thenReturn(entities);
            when(world.getPlayers()).thenReturn(List.of());
            when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true))).thenAnswer(call -> new CompletableFuture<Chunk>());
        }

        void completeChunkLoads() {
            var server = mock(Server.class);
            var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
            when(plugin.getServer()).thenReturn(server);
            when(server.getScheduler()).thenReturn(scheduler);
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L))).thenAnswer(call -> {
                entityPoll = call.getArgument(1);
                return pollTask;
            });
            when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true))).thenAnswer(call -> {
                var chunk = mock(Chunk.class);
                when(chunk.isEntitiesLoaded()).thenAnswer(ignored -> entitiesLoaded.get());
                chunks.add(chunk);
                return CompletableFuture.completedFuture(chunk);
            });
            var air = mock(org.bukkit.block.Block.class);
            when(air.getType()).thenReturn(Material.AIR);
            when(air.isPassable()).thenReturn(true);
            var obsidian = mock(org.bukkit.block.Block.class);
            when(obsidian.getType()).thenReturn(Material.OBSIDIAN);
            when(world.getMaxHeight()).thenReturn(256);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> (int) call.getArgument(1) == 70 ? obsidian : air);
        }

        org.mockito.MockedConstruction<WorldCreator> creators() {
            return mockConstruction(WorldCreator.class, (creator, context) -> {
                when(creator.environment(World.Environment.THE_END)).thenReturn(creator);
                when(creator.generateStructures(true)).thenReturn(creator);
                when(creator.createWorld()).thenReturn(world);
            });
        }

        ManhuntWorld prepare() {
            var prepared = new ManhuntWorld(plugin);
            prepared.prepare(() -> true);
            return prepared;
        }

        void saveReady(String name) throws Exception {
            var yaml = new YamlConfiguration();
            yaml.set("world", name);
            yaml.set("state", "READY");
            yaml.set("folder", folder.toString());
            yaml.save(folder.resolve("manhunt-world.yml").toFile());
        }

        <T extends Mob> T mob(Class<T> type, boolean initialAi, boolean initialGravity) {
            var mob = mock(type);
            var ai = new AtomicBoolean(initialAi);
            var gravity = new AtomicBoolean(initialGravity);
            when(mob.getUniqueId()).thenReturn(UUID.randomUUID());
            when(mob.getLocation()).thenReturn(new Location(world, 0, 110, 0));
            when(mob.isValid()).thenReturn(true);
            when(mob.hasAI()).thenAnswer(call -> ai.get());
            when(mob.hasGravity()).thenAnswer(call -> gravity.get());
            doAnswer(call -> { ai.set(call.getArgument(0)); return null; }).when(mob).setAI(anyBoolean());
            doAnswer(call -> { gravity.set(call.getArgument(0)); return null; }).when(mob).setGravity(anyBoolean());
            var data = mock(PersistentDataContainer.class);
            Map<NamespacedKey, Byte> values = new HashMap<>();
            when(data.get(any(), eq(PersistentDataType.BYTE))).thenAnswer(call -> values.get(call.getArgument(0)));
            when(data.isEmpty()).thenAnswer(call -> values.isEmpty());
            doAnswer(call -> { values.put(call.getArgument(0), call.getArgument(2)); return null; })
                    .when(data).set(any(), eq(PersistentDataType.BYTE), any());
            doAnswer(call -> { values.remove(call.getArgument(0)); return null; }).when(data).remove(any());
            when(mob.getPersistentDataContainer()).thenReturn(data);
            return mob;
        }
    }
}
