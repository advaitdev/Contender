package me.advait.contender.manhunt;

import me.advait.contender.kit.Kit;
import me.advait.contender.testutil.StateTestServer;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManhuntSessionTest {
    private static final class Fixture implements AutoCloseable {
        final StateTestServer env = new StateTestServer();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final ManhuntManager manager = mock(ManhuntManager.class);
        final ManhuntWorld prepared = mock(ManhuntWorld.class);
        final World world = mock(World.class);
        final EnderDragon dragon = mock(EnderDragon.class);
        final Kit kit = mock(Kit.class);
        final Player runner = player("Alice"), hunter = player("Bob");
        final ManhuntRun run = new ManhuntRun(UUID.randomUUID(), "Manhunt", "axe", "end", List.of(
                new ManhuntRun.Entry(runner.getUniqueId(), "Alice", ManhuntRun.Team.RUNNER),
                new ManhuntRun.Entry(hunter.getUniqueId(), "Bob", ManhuntRun.Team.HUNTER)), 3);
        final ManhuntSession session = new ManhuntSession(env.plugin, manager, run, prepared, kit);
        Fixture() {
            when(prepared.world()).thenReturn(world); when(prepared.contains(any(Location.class))).thenAnswer(c -> ((Location) c.getArgument(0)).getWorld() == world);
            when(prepared.spawn(anyInt())).thenAnswer(c -> new Location(world, (int) c.getArgument(0) * 20, 90, 0));
            when(world.getEntitiesByClass(EnderDragon.class)).thenReturn(List.of(dragon));
            when(dragon.isValid()).thenReturn(true); when(dragon.getHealth()).thenReturn(200d); when(dragon.getLocation()).thenAnswer(c -> new Location(world, 0, 100, 0));
            bukkit.when(() -> Bukkit.getPlayer(runner.getUniqueId())).thenReturn(runner); bukkit.when(() -> Bukkit.getPlayer(hunter.getUniqueId())).thenReturn(hunter);
            session.enable();
        }
        Player player(String name) {
            Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getName()).thenReturn(name);
            when(player.getInventory()).thenReturn(mock(PlayerInventory.class)); when(player.isOnline()).thenReturn(true); when(player.isCollidable()).thenReturn(true);
            when(player.getLocation()).thenAnswer(c -> new Location(world, 0, 90, 0)); when(player.teleport(any(Location.class))).thenReturn(true); return player;
        }
        void start() { for (int i = 0; i < 3; i++) env.scheduled.getFirst().run(); assertEquals(ManhuntRun.State.RUNNING, run.state()); }
        @Override public void close() { session.disable(); bukkit.close(); env.close(); }
    }
    @Test void countdownFreezesMovementAndDamageThenStartsWithoutLingeringTasks() {
        try (var f = new Fixture()) {
            assertEquals(ManhuntRun.State.COUNTDOWN, f.run.state()); verify(f.kit).apply(f.runner); verify(f.kit).apply(f.hunter);
            var moved = new PlayerMoveEvent(f.runner, new Location(f.world, 0, 90, 0), new Location(f.world, 1, 90, 0, 80, 25));
            f.session.move(moved); assertEquals(0, moved.getTo().getX()); assertEquals(80, moved.getTo().getYaw());
            var damage = damage(f.runner, EntityDamageEvent.DamageCause.FALL); f.session.damage(damage); verify(damage).setCancelled(true);
            f.start(); verify(f.prepared).thaw(); verify(f.runner).setCollidable(true);
            f.session.disable(); verify(f.manager).restore(f.runner); verify(f.manager).restore(f.hunter);
            clearInvocations(f.manager); f.env.scheduled.forEach(StateTestServer.Scheduled::run); verifyNoInteractions(f.manager);
        }
    }
    @Test void hunterDeathCreatesSpectatorWithoutRespawningIntoCombatOrEndingGame() {
        try (var f = new Fixture()) {
            f.start(); when(f.hunter.isDead()).thenReturn(true);
            var death = mock(PlayerDeathEvent.class); when(death.getPlayer()).thenReturn(f.hunter); when(death.getDrops()).thenReturn(new ArrayList<>());
            f.session.death(death); assertFalse(f.run.alive(f.hunter.getUniqueId())); assertFalse(f.run.terminal()); verify(f.manager, never()).completed();
            var respawn = mock(PlayerRespawnEvent.class); when(respawn.getPlayer()).thenReturn(f.hunter); f.session.respawn(respawn); f.env.scheduled.getLast().run();
            verify(f.hunter).setGameMode(GameMode.SPECTATOR); verify(f.kit, times(1)).apply(f.hunter);
            assertFalse(f.session.playing(f.hunter.getUniqueId())); assertTrue(f.session.owns(f.hunter.getUniqueId()));
        }
    }
    @Test void aCountdownCleanupErrorDoesNotPreventCollisionOrInventoryRestoration() {
        try (var f = new Fixture()) {
            when(f.env.plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            doThrow(new IllegalStateException("connection failed")).when(f.runner).sendActionBar(any(Component.class));
            f.session.disable();
            verify(f.runner).setCollidable(true); verify(f.hunter).setCollidable(true);
            verify(f.manager).restore(f.runner); verify(f.manager).restore(f.hunter);
            f.env.scheduled.forEach(task -> verify(task.task()).cancel());
        }
    }
    @Test void liveDisconnectUsesTheSameOneLifeRuleAndRejoinOnlySpectates() {
        try (var f = new Fixture()) {
            f.start(); var quit = mock(PlayerQuitEvent.class); when(quit.getPlayer()).thenReturn(f.hunter);
            f.session.quit(quit); assertFalse(f.run.alive(f.hunter.getUniqueId())); assertFalse(f.run.terminal());
            f.session.join(new PlayerJoinEvent(f.hunter, Component.empty())); f.env.scheduled.getLast().run();
            verify(f.hunter).setGameMode(GameMode.SPECTATOR); verify(f.kit, times(1)).apply(f.hunter);
            when(quit.getPlayer()).thenReturn(f.runner); f.session.quit(quit);
            assertEquals(ManhuntRun.State.HUNTERS_WON, f.run.state()); verify(f.manager).completed();
        }
    }
    @Test void dragonKilledWithoutAPlayerKillerAwardsRunnersAndOutsidersCannotHurtPlayers() {
        try (var f = new Fixture()) {
            f.start(); var outsider = f.player("Director"); var attack = mock(EntityDamageByEntityEvent.class);
            when(attack.getEntity()).thenReturn(f.runner); when(attack.getDamager()).thenReturn(outsider); f.session.damage(attack); verify(attack).setCancelled(true);
            var death = mock(EntityDeathEvent.class); when(death.getEntity()).thenReturn(f.dragon); f.session.dragonDeath(death);
            assertEquals(ManhuntRun.State.RUNNERS_WON, f.run.state()); verify(f.manager).runnersWon();
            verify(f.manager, never()).completed(); verify(f.manager, never()).finish(any(), anyBoolean());
            var returnTask = f.env.scheduled.getLast(); assertEquals(200, returnTask.delay()); assertFalse(returnTask.repeating());
            assertTrue(f.session.owns(f.runner.getUniqueId())); assertTrue(f.session.owns(f.hunter.getUniqueId()));
            verify(f.manager, never()).restore(any()); verify(f.prepared, never()).freeze();
            returnTask.run(); verify(f.manager).finish(ManhuntRun.State.RUNNERS_WON, false);
        }
    }
    @Test void deathAnimationStartsTheWinDelayOnceAndDamageCannotChangeTheResult() {
        try (var f = new Fixture()) {
            f.start(); when(f.dragon.getDeathAnimationTicks()).thenReturn(1);
            var tick = f.env.scheduled.get(1);
            tick.run();
            int tasks = f.env.scheduled.size();
            tick.run();
            var death = mock(EntityDeathEvent.class); when(death.getEntity()).thenReturn(f.dragon); f.session.dragonDeath(death);
            assertEquals(tasks, f.env.scheduled.size()); verify(f.manager).runnersWon();
            var fall = damage(f.runner, EntityDamageEvent.DamageCause.FALL); f.session.damage(fall); verify(fall).setCancelled(true);
            var quit = mock(PlayerQuitEvent.class); when(quit.getPlayer()).thenReturn(f.runner); f.session.quit(quit);
            assertEquals(ManhuntRun.State.RUNNERS_WON, f.run.state());
            verify(f.manager, never()).completed(); verify(f.prepared, never()).freeze();
        }
    }
    @Test void cancellingDuringTheDeathAnimationInvalidatesTheDelayedFinish() {
        try (var f = new Fixture()) {
            f.start(); when(f.dragon.getDeathAnimationTicks()).thenReturn(1);
            f.env.scheduled.get(1).run(); var returnTask = f.env.scheduled.getLast();
            f.session.disable();
            verify(returnTask.task()).cancel();
            returnTask.run();
            verify(f.manager, never()).finish(any(), anyBoolean());
            verify(f.manager).restore(f.runner); verify(f.manager).restore(f.hunter);
        }
    }
    @Test void kitHurtFlagsProtectHealthButVoidRemainsLethalAndHungerStaysFull() {
        try (var f = new Fixture()) {
            f.start(); when(f.kit.isPveHurt()).thenReturn(true); when(f.kit.keepsHungerFull()).thenReturn(true);
            var fall = damage(f.runner, EntityDamageEvent.DamageCause.FALL); f.session.damage(fall); verify(fall).setDamage(0d); verify(fall, never()).setCancelled(true);
            var intoVoid = damage(f.runner, EntityDamageEvent.DamageCause.VOID); f.session.damage(intoVoid); verify(intoVoid, never()).setDamage(anyDouble()); verify(intoVoid, never()).setCancelled(true);
            var food = mock(FoodLevelChangeEvent.class); when(food.getEntity()).thenReturn(f.runner); f.session.hunger(food);
            verify(food).setCancelled(true); verify(f.runner).setFoodLevel(20); verify(f.runner).setExhaustion(0);
        }
    }
    private EntityDamageEvent damage(Player player, EntityDamageEvent.DamageCause cause) {
        EntityDamageEvent event = mock(EntityDamageEvent.class); when(event.getEntity()).thenReturn(player); when(event.getCause()).thenReturn(cause); return event;
    }
}
