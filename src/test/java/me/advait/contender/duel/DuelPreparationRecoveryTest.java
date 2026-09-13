package me.advait.contender.duel;

import me.advait.contender.arena.ArenaInstance;
import me.advait.contender.arena.ArenaLease;
import me.advait.contender.arena.ArenaManager;
import me.advait.contender.kit.Kit;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.spectator.ArenaProtectionListener;
import me.advait.contender.spectator.SpectatorVisibility;
import me.advait.contender.testutil.StateTestServer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DuelPreparationRecoveryTest {
    @Test void blockedStartRetainsTheDuelAndWaitsBeforeGivingEitherPlayerTheKit() {
        try (Fixture f = new Fixture()) {
            f.second.blockTeleport = true;
            f.duel.start();
            assertInstanceOf(StartingState.class, f.duel.getState());
            assertTrue(f.duel.getState().isEnabled());
            verify(f.kit, never()).apply(any());
            verify(f.first.player, never()).setGameMode(any());
            verify(f.second.player, never()).setGameMode(any());
            Location moved = f.first.location.clone().add(1, 0, 0);
            moved.setYaw(80); moved.setPitch(30);
            var movement = new PlayerMoveEvent(f.first.player, f.first.location.clone(), moved);
            f.containment.onMove(movement);
            assertEquals(f.first.location.toVector(), movement.getTo().toVector());
            assertEquals(80, movement.getTo().getYaw());
            var teleport = new PlayerTeleportEvent(f.first.player, f.first.location.clone(), f.lobbySpawn.clone(), PlayerTeleportEvent.TeleportCause.PLUGIN);
            f.containment.onTeleport(teleport);
            assertTrue(teleport.isCancelled(), "Only the exact checked spawn teleport bypasses the preparation hold");

            f.second.blockTeleport = false;
            var retry = f.runRetry();
            assertInstanceOf(SortingState.class, f.duel.getState());
            verify(f.kit).apply(f.first.player);
            verify(f.kit).apply(f.second.player);
            assertEquals(f.firstSpawn, f.first.location);
            assertEquals(f.secondSpawn, f.second.location);
            assertEquals(0, f.duel.getCurrentRound());
            retry.run();
            verify(f.kit, times(1)).apply(f.first.player);
            verify(f.duel, never()).forceEnd();
        }
    }

    @Test void anOnlineDeadPlayerWaitsForRespawnBeforeEitherContestantMoves() {
        try (Fixture f = new Fixture()) {
            f.first.dead = true;
            f.duel.start();
            assertTrue(f.first.player.isOnline());
            assertInstanceOf(StartingState.class, f.duel.getState());
            verify(f.first.player, never()).teleport(any(Location.class));
            verify(f.second.player, never()).teleport(any(Location.class));
            verify(f.kit, never()).apply(any());
            f.first.dead = false;
            f.runRetry();
            assertInstanceOf(SortingState.class, f.duel.getState());
            verify(f.duel, never()).forceEnd();
        }
    }

    @Test void aRedirectedTeleportCannotStartTheDuelInTheWrongWorld() {
        try (Fixture f = new Fixture()) {
            f.second.redirect = f.lobbySpawn;
            f.duel.start();
            assertInstanceOf(StartingState.class, f.duel.getState());
            verify(f.kit, never()).apply(any());
            f.second.redirect = null;
            f.runRetry();
            assertInstanceOf(SortingState.class, f.duel.getState());
            assertEquals(f.secondSpawn, f.second.location);
        }
    }

    @Test void roundPreparationKeepsTheScoreAndBlocksCombatUntilTheRetrySucceeds() {
        try (Fixture f = new Fixture()) {
            f.beginRound();
            f.duel.getTeam1().incrementScore();
            f.first.location = f.lobbySpawn.clone();
            f.first.blockTeleport = true;
            clearInvocations(f.duel);
            ActiveState preparing = new ActiveState(f.duel);
            f.duel.setState(preparing);
            assertFalse(f.duel.isCombatActive());
            assertFalse(preparing.canAddSpectator());
            assertEquals(1, f.duel.getCurrentRound());
            assertEquals(1, f.duel.getTeam1().getScore());
            var damage = mock(EntityDamageEvent.class);
            when(damage.getEntity()).thenReturn(f.first.player);
            preparing.onDamage(damage);
            verify(damage).setCancelled(true);
            var attack = mock(EntityDamageByEntityEvent.class);
            when(attack.getDamager()).thenReturn(f.first.player);
            when(attack.getEntity()).thenReturn(f.second.player);
            preparing.onAttack(attack);
            verify(attack).setCancelled(true);
            var death = mock(PlayerDeathEvent.class);
            when(death.getEntity()).thenReturn(f.first.player);
            when(death.getDrops()).thenReturn(new ArrayList<>());
            preparing.onDeath(death);
            verify(f.duel, never()).handleDeath(any(), any());
            verify(f.duel, never()).broadcastSound(Duel.SoundType.COUNTDOWN_GO);

            f.first.blockTeleport = false;
            f.runRetry();
            assertTrue(f.duel.isCombatActive());
            assertEquals(2, f.duel.getCurrentRound());
            assertEquals(1, f.duel.getTeam1().getScore());
            verify(f.duel, times(1)).broadcastSound(Duel.SoundType.COUNTDOWN_GO);
            clearInvocations(f.first.player);
            for (var task : List.copyOf(f.server.scheduled)) if (task.repeating() && task.delay() == 1) task.run();
            verify(f.first.player, never()).setVelocity(any());
        }
    }

    @Test void noClearKeepsTheOriginalInventorySnapshotAcrossPreparationRetries() throws Exception {
        try (Fixture f = new Fixture()) {
            f.kit.setNoClear(true);
            ItemStack original = mock(ItemStack.class), changed = mock(ItemStack.class);
            when(original.clone()).thenReturn(original); when(changed.clone()).thenReturn(changed);
            f.first.storage = new ItemStack[]{original};
            doThrow(new IllegalStateException("Temporary equipment failure")).doCallRealMethod().when(f.kit).applyEffectsOnly(f.second.player);
            f.duel.start();
            assertInstanceOf(StartingState.class, f.duel.getState());
            f.first.storage = new ItemStack[]{changed};
            f.runRetry();
            assertInstanceOf(SortingState.class, f.duel.getState());
            verify(f.kit, times(1)).applyEffectsOnly(f.first.player);
            var restore = Duel.class.getDeclaredMethod("restorePreDuelInventory", UUID.class, Player.class);
            restore.setAccessible(true);
            restore.invoke(f.duel, f.first.id, f.first.player);
            assertArrayEquals(new ItemStack[]{original}, f.first.storage);
        }
    }

    @Test void reducedMaximumHealthWorksDuringBothKitApplicationAndLaterRounds() {
        try (Fixture f = new Fixture()) {
            f.first.maxHealth = 8;
            f.beginRound();
            assertEquals(8, f.first.health);
            f.first.health = 2;
            f.duel.setState(new ActiveState(f.duel));
            assertTrue(f.duel.isCombatActive());
            assertEquals(2, f.duel.getCurrentRound());
            assertEquals(8, f.first.health);
        }
    }

    @Test void failedStartSoundCannotCancelAPreparedRound() {
        try (Fixture f = new Fixture()) {
            f.duel.start();
            doThrow(new IllegalStateException("Sound unavailable")).when(f.duel).broadcastSound(Duel.SoundType.COUNTDOWN_GO);
            f.duel.setState(new ActiveState(f.duel));
            assertTrue(f.duel.isCombatActive());
            assertEquals(1, f.duel.getCurrentRound());
            verify(f.duel, never()).shutdown();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final StateTestServer server = new StateTestServer();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<io.papermc.paper.registry.RegistryAccess> registries = mockStatic(io.papermc.paper.registry.RegistryAccess.class);
        final MockedStatic<io.papermc.paper.InternalAPIBridge> bridges = mockStatic(io.papermc.paper.InternalAPIBridge.class);
        final MockedConstruction<SpectatorVisibility> visibility = mockConstruction(SpectatorVisibility.class);
        final World world = mock(World.class), lobbyWorld = mock(World.class);
        final Location firstSpawn = new Location(world, 35, 65, 35), secondSpawn = new Location(world, 45, 65, 45);
        final Location lobbySpawn = new Location(lobbyWorld, 0, 65, 0);
        final TestPlayer first = new TestPlayer(lobbySpawn), second = new TestPlayer(lobbySpawn);
        final Map<UUID, TestPlayer> players = Map.of(first.id, first, second.id, second);
        final Kit kit = spy(new Kit("sword"));
        final DuelManager manager = mock(DuelManager.class);
        final ArenaManager arenas = mock(ArenaManager.class);
        final Duel duel;
        final ArenaProtectionListener containment;
        int taskIndex;

        Fixture() {
            var bridge = mock(io.papermc.paper.InternalAPIBridge.class);
            bridges.when(io.papermc.paper.InternalAPIBridge::get).thenReturn(bridge);
            var access = mock(io.papermc.paper.registry.RegistryAccess.class, RETURNS_MOCKS);
            registries.when(io.papermc.paper.registry.RegistryAccess::registryAccess).thenReturn(access);
            var rules = new java.util.HashMap<net.kyori.adventure.key.Key, org.bukkit.GameRule<?>>();
            var gameRules = org.bukkit.Registry.GAME_RULE;
            doAnswer(call -> rules.computeIfAbsent(call.getArgument(0), key -> mock(org.bukkit.GameRule.class)))
                    .when(gameRules).getOrThrow(any(net.kyori.adventure.key.Key.class));
            Logger logger = mock(Logger.class);
            when(server.plugin.getLogger()).thenReturn(logger);
            when(server.plugin.getArenaManager()).thenReturn(arenas);
            when(world.getName()).thenReturn("arenas");
            when(lobbyWorld.getName()).thenReturn("lobby");
            ArenaMap map = mock(ArenaMap.class);
            when(map.getId()).thenReturn("arena"); when(map.getWorldName()).thenReturn("arenas");
            when(map.getTeam1Spawn()).thenReturn(firstSpawn); when(map.getTeam2Spawn()).thenReturn(secondSpawn);
            ArenaInstance instance = mock(ArenaInstance.class);
            when(instance.map()).thenReturn(map);
            DuelSetup setup = new DuelSetup(first.id);
            setup.setSelectedMap(map); setup.setSelectedKit(kit);
            setup.getTeam1().addPlayer(first.id); setup.getTeam2().addPlayer(second.id);
            duel = spy(new Duel(server.plugin, manager, setup, new ArenaLease(instance, UUID.randomUUID()), true));
            // Mockito copies the Duel; the constructor's state must reference that copy too.
            try {
                var initialState = Duel.class.getDeclaredField("state");
                initialState.setAccessible(true);
                initialState.set(duel, new StartingState(duel));
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            containment = new ArenaProtectionListener(manager, arenas);
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(call -> {
                TestPlayer player = players.get(call.getArgument(0)); return player == null ? null : player.player;
            });
            bukkit.when(() -> Bukkit.getOfflinePlayer(any(UUID.class))).thenAnswer(call -> {
                TestPlayer player = players.get(call.getArgument(0)); return player == null ? null : player.player;
            });
            for (TestPlayer player : players.values()) configure(player);
        }

        private void configure(TestPlayer state) {
            when(state.player.getUniqueId()).thenReturn(state.id);
            when(state.player.getName()).thenReturn("Player");
            when(state.player.isOnline()).thenReturn(true);
            when(state.player.isDead()).thenAnswer(call -> state.dead);
            when(state.player.getWorld()).thenAnswer(call -> state.location.getWorld());
            when(state.player.getLocation()).thenAnswer(call -> state.location.clone());
            when(state.player.getInventory()).thenReturn(state.inventory);
            when(state.inventory.getStorageContents()).thenAnswer(call -> state.storage.clone());
            when(state.inventory.getArmorContents()).thenReturn(new ItemStack[4]);
            doAnswer(call -> { state.storage = ((ItemStack[]) call.getArgument(0)).clone(); return null; })
                    .when(state.inventory).setStorageContents(any());
            when(state.player.getMaxHealth()).thenAnswer(call -> state.maxHealth);
            doAnswer(call -> {
                double health = call.getArgument(0);
                if (health > state.maxHealth) throw new IllegalArgumentException("Health exceeds maximum");
                state.health = health; return null;
            }).when(state.player).setHealth(anyDouble());
            when(manager.getDuel(state.player)).thenReturn(duel);
            when(manager.getDuel(state.id)).thenReturn(duel);
            when(state.player.teleport(any(Location.class))).thenAnswer(call -> {
                Location destination = call.getArgument(0);
                var event = new PlayerTeleportEvent(state.player, state.location.clone(), destination.clone(), PlayerTeleportEvent.TeleportCause.PLUGIN);
                containment.onTeleport(event);
                if (event.isCancelled() || state.blockTeleport) return false;
                state.location = state.redirect == null ? event.getTo().clone() : state.redirect.clone();
                return true;
            });
        }

        StateTestServer.Scheduled runRetry() {
            while (taskIndex < server.scheduled.size()) {
                var task = server.scheduled.get(taskIndex++);
                if (!task.repeating()) { task.run(); return task; }
            }
            throw new AssertionError("No preparation retry scheduled");
        }

        void beginRound() {
            duel.start();
            assertInstanceOf(SortingState.class, duel.getState());
            duel.captureInventories();
            duel.setState(new ActiveState(duel));
            assertTrue(duel.isCombatActive());
        }

        @Override public void close() {
            duel.getState().disable(); visibility.close(); bridges.close(); registries.close(); bukkit.close(); server.close();
        }
    }

    private static final class TestPlayer {
        final UUID id = UUID.randomUUID();
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        Location location, redirect;
        ItemStack[] storage = new ItemStack[36];
        boolean dead, blockTeleport;
        double maxHealth = 20, health = 20;
        TestPlayer(Location location) { this.location = location.clone(); }
    }
}
