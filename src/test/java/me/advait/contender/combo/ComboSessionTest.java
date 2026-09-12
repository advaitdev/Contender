package me.advait.contender.combo;

import me.advait.contender.Contender;
import me.advait.contender.arena.*;
import me.advait.contender.game.AbstractGameState;
import me.advait.contender.map.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ComboSessionTest {
    private Contender plugin;
    private ComboManager manager;
    private ComboRun run;
    private ComboSession session;
    private Player player;
    private Mannequin bot;
    private World world;
    private final List<Runnable> queued = new ArrayList<>();
    private MockedStatic<Bukkit> bukkit;
    @BeforeEach void setup() throws Exception {
        plugin = mock(Contender.class); manager = mock(ComboManager.class); player = mock(Player.class); bot = mock(Mannequin.class); world = mock(World.class);
        Server server = mock(Server.class); BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server); when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenAnswer(call -> { queued.add(call.getArgument(1)); return mock(BukkitTask.class); });
        run = ComboRunTest.run(1, 5); UUID id = run.entries().getFirst().id(); run.start(); run.begin(id);
        when(player.getUniqueId()).thenReturn(id); when(bot.getUniqueId()).thenReturn(UUID.randomUUID());
        when(world.getName()).thenReturn("arena"); when(world.getEntities()).thenReturn(List.of(bot, player));
        doAnswer(call -> { when(world.getEntities()).thenReturn(List.of(player)); return null; }).when(bot).remove();
        when(player.getLocation()).thenReturn(new Location(world, 1, 65, 1)); when(bot.getLocation()).thenReturn(new Location(world, 3, 65, 1));
        when(bot.isValid()).thenReturn(true); when(bot.getMaxHealth()).thenReturn(1024.0);
        var inventory = mock(PlayerInventory.class); var sword = mock(ItemStack.class);
        when(sword.getType()).thenReturn(Material.DIAMOND_SWORD); when(player.getInventory()).thenReturn(inventory); when(inventory.getItemInMainHand()).thenReturn(sword);
        var map = new ArenaMap("platform"); map.setWorldName("arena"); map.setRollbackRegion(0, 60, 0, 10, 70, 10);
        map.setTeam1Spawn(1, 65, 1, 0, 0); map.setTeam2Spawn(3, 65, 1, 0, 0);
        var instance = new ArenaInstance(0, map, new BlockBounds(-100, -64, -100, 100, 320, 100));
        session = new ComboSession(plugin, manager, run, new ArenaLease(instance, UUID.randomUUID()));
        field(session, "bot", bot); field(session, "fighter", id); field(session, "motion", mock(MannequinMotion.class));
        field(AbstractGameState.class, session, "enabled", true);
        @SuppressWarnings("unchecked") Set<UUID> entered = (Set<UUID>) get(session, "entered"); entered.add(id);
        bukkit = mockStatic(Bukkit.class); bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player)); bukkit.when(() -> Bukkit.getWorld("arena")).thenReturn(world);
    }
    @AfterEach void close() { bukkit.close(); }
    private EntityDamageByEntityEvent hit(Entity attacker, Entity victim) {
        var event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(attacker); when(event.getEntity()).thenReturn(victim);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK); when(event.getDamage()).thenReturn(7.0);
        return event;
    }
    @Test void actualSwordHitStartsTheIdleBotWithoutRemovingNativeHitDamage() throws Exception {
        var event = hit(player, bot); session.damage(event); session.score(event);
        verify(event, never()).setCancelled(true); assertEquals(1, run.hits()); assertEquals(true, get(session, "live"));
        assertEquals(5, get(session, "reactAt"));
        session.score(event); assertEquals(1, run.hits(), "A duplicate event in the same tick must not count twice");
    }
    @Test void onlyCurrentPlayersDirectSwordHitsCanStartTheAttempt() {
        var outsider = mock(Player.class); when(outsider.getUniqueId()).thenReturn(UUID.randomUUID());
        var outsideHit = hit(outsider, bot); session.damage(outsideHit); verify(outsideHit).setCancelled(true);
        var projectile = hit(mock(Arrow.class), bot); session.damage(projectile); verify(projectile).setCancelled(true);
        var sweep = hit(player, bot); when(sweep.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK);
        session.damage(sweep); verify(sweep).setCancelled(true); assertEquals(0, run.hits());
    }
    @Test void fifthReturnedHitRetriesButSixthRecordsResultAndReturnsPlayer() throws Exception {
        field(session, "live", true); for (int i = 0; i < 5; i++) run.hit(player.getUniqueId());
        var returned = hit(bot, player); session.damage(returned); session.score(returned);
        verify(returned, never()).setCancelled(true); assertEquals(0, run.hits()); assertFalse(run.entries().getFirst().done());
        assertEquals(1, queued.size()); queued.clear(); field(session, "resettingTurn", false);
        for (int i = 0; i < 6; i++) run.hit(player.getUniqueId()); session.score(hit(bot, player));
        assertEquals(6, run.entries().getFirst().score()); assertNull(run.playing());
        queued.getFirst().run(); verify(manager).restore(player); assertFalse(session.owns(player.getUniqueId())); verify(bot).remove();
    }
    @Test void genuineFallBelowArenaClearsAndUnaidedIdleFallDoesNot() throws Exception {
        field(session, "live", true); run.hit(player.getUniqueId());
        when(bot.getLocation()).thenReturn(new Location(world, 15, 56, 1));
        Method tick = ComboSession.class.getDeclaredMethod("tick"); tick.setAccessible(true); tick.invoke(session);
        assertTrue(run.entries().getFirst().cleared()); assertEquals("∞", run.entries().getFirst().value());
        assertEquals(true, get(session, "cleared")); queued.getFirst().run(); verify(manager).restore(player);
    }
    @Test void spectatorDamageAndEnvironmentCannotEndACombo() throws Exception {
        var hazard = mock(EntityDamageEvent.class); when(hazard.getEntity()).thenReturn(player);
        when(hazard.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL); session.damage(hazard); verify(hazard).setCancelled(true);
        var idleAttack = hit(bot, player); session.damage(idleAttack); verify(idleAttack).setCancelled(true);
        assertEquals(0, run.hits()); assertFalse(run.entries().getFirst().done());
    }
    @Test void shieldedSwingsAndThornsDoNotEndThePlayersTurn() throws Exception {
        field(session, "live", true); for (int i = 0; i < 8; i++) run.hit(player.getUniqueId());
        var blocked = hit(bot, player);
        when(blocked.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)).thenReturn(true);
        when(blocked.getDamage(EntityDamageEvent.DamageModifier.BLOCKING)).thenReturn(-7.0);
        session.score(blocked); assertEquals(8, run.hits()); assertFalse(run.entries().getFirst().done());
        var thorns = hit(bot, player); when(thorns.getCause()).thenReturn(EntityDamageEvent.DamageCause.THORNS);
        session.damage(thorns); verify(thorns).setCancelled(true); session.score(thorns);
        assertEquals(8, run.hits()); assertFalse(run.entries().getFirst().done()); assertTrue(queued.isEmpty());
    }
    @Test void watcherCanLeaveWithoutLosingTheirPlaceInTheQueue() throws Exception {
        field(session, "fighter", null); run.disconnect(player.getUniqueId());
        @SuppressWarnings("unchecked") Set<UUID> watchers = (Set<UUID>) get(session, "watchers"); watchers.add(player.getUniqueId());
        when(manager.restore(player)).thenReturn(true);
        assertTrue(session.unwatch(player)); verify(manager).restore(player);
        assertFalse(session.owns(player.getUniqueId())); assertFalse(run.entries().getFirst().done());
        assertFalse(session.unwatch(player));
    }
    @Test void currentFighterCannotLeaveThroughTheSpectatorExit() {
        assertFalse(session.unwatch(player)); verify(manager, never()).restore(player);
        assertEquals(player.getUniqueId(), run.playing());
    }
    @Test void retryCallbackDoesNotActOnAReplacementOrDisconnectedPlayer() throws Exception {
        field(session, "live", true); run.hit(player.getUniqueId()); session.score(hit(bot, player));
        field(session, "fighter", null);
        assertDoesNotThrow(() -> queued.getFirst().run()); verify(player, never()).teleport(any(Location.class));
    }
    private static Object get(Object object, String name) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private static void field(Object object, String name, Object value) throws Exception { field(object.getClass(), object, name, value); }
    private static void field(Class<?> type, Object object, String name, Object value) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
}
