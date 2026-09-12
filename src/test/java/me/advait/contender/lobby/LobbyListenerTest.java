package me.advait.contender.lobby;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import me.advait.contender.duel.Duel;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.race.RaceManager;
import me.advait.contender.role.RoleManager;
import me.advait.contender.testutil.StateTestServer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.junit.jupiter.api.*;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LobbyListenerTest {
    private final StateTestServer server = new StateTestServer();
    private final YamlConfiguration config = new YamlConfiguration();
    private final DuelManager duels = mock(DuelManager.class);
    private final RoleManager roles = mock(RoleManager.class);
    private final Player player = mock(Player.class);
    private final World world = mock(World.class);
    private LobbyManager lobby;
    private LobbyListener listener;

    @BeforeEach void setup() {
        when(server.plugin.getConfig()).thenReturn(config);
        when(server.plugin.getDuelManager()).thenReturn(duels);
        when(server.plugin.getRoleManager()).thenReturn(roles);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        lobby = spy(new LobbyManager(server.plugin));
        doReturn(true).when(lobby).isLobbyWorld(world);
        listener = new LobbyListener(server.plugin, lobby);
    }

    @AfterEach void close() { server.close(); }

    private BlockBreakEvent breakBlock() {
        var event = mock(BlockBreakEvent.class, RETURNS_DEEP_STUBS);
        when(event.getBlock().getWorld()).thenReturn(world); when(event.getPlayer()).thenReturn(player);
        listener.onBlockBreak(event); return event;
    }
    private BlockPlaceEvent placeBlock() {
        var event = mock(BlockPlaceEvent.class, RETURNS_DEEP_STUBS);
        when(event.getBlock().getWorld()).thenReturn(world); when(event.getPlayer()).thenReturn(player);
        listener.onBlockPlace(event); return event;
    }

    @Test void defaultsProtectTheLobbyAndAllowAdminsToBuildWithADisableableBypass() {
        verify(breakBlock()).setCancelled(true); verify(placeBlock()).setCancelled(true);
        when(player.hasPermission("contender.admin")).thenReturn(true);
        verify(breakBlock(), never()).setCancelled(anyBoolean()); verify(placeBlock(), never()).setCancelled(anyBoolean());
        config.set("lobby.admin-build-bypass", false);
        verify(breakBlock()).setCancelled(true); verify(placeBlock()).setCancelled(true);
    }

    @Test void breakingAndPlacingAreIndependentAndOtherWorldsAreUnaffected() {
        config.set("lobby.allow-block-break", true); config.set("lobby.allow-block-place", false);
        verify(breakBlock(), never()).setCancelled(anyBoolean()); verify(placeBlock()).setCancelled(true);
        config.set("lobby.allow-block-break", false); config.set("lobby.allow-block-place", true);
        verify(breakBlock()).setCancelled(true); verify(placeBlock(), never()).setCancelled(anyBoolean());
        config.set("lobby.allow-block-place", false); doReturn(false).when(lobby).isLobbyWorld(world);
        verify(breakBlock(), never()).setCancelled(anyBoolean()); verify(placeBlock(), never()).setCancelled(anyBoolean());
    }

    @Test void blockCracksAndBucketsUseTheSameBuildPermissions() {
        var damage = mock(BlockDamageEvent.class, RETURNS_DEEP_STUBS);
        when(damage.getBlock().getWorld()).thenReturn(world); when(damage.getPlayer()).thenReturn(player);
        var fill = mock(PlayerBucketFillEvent.class, RETURNS_DEEP_STUBS);
        when(fill.getBlock().getWorld()).thenReturn(world); when(fill.getPlayer()).thenReturn(player);
        var empty = mock(PlayerBucketEmptyEvent.class, RETURNS_DEEP_STUBS);
        when(empty.getBlock().getWorld()).thenReturn(world); when(empty.getPlayer()).thenReturn(player);
        listener.onBlockDamageEarly(damage); listener.onBucketFill(fill); listener.onBucketEmpty(empty);
        verify(damage).setCancelled(true); verify(fill).setCancelled(true); verify(empty).setCancelled(true);
        clearInvocations(damage, fill, empty);
        when(player.hasPermission("contender.admin")).thenReturn(true);
        listener.onBlockDamageEarly(damage); listener.onBucketFill(fill); listener.onBucketEmpty(empty);
        verify(damage, never()).setCancelled(anyBoolean()); verify(fill, never()).setCancelled(anyBoolean()); verify(empty, never()).setCancelled(anyBoolean());
    }

    @Test void decorationEditingFollowsBuildAccessWithoutChangingPlayerDamageRules() {
        var pot = mock(PlayerInteractEvent.class, RETURNS_DEEP_STUBS);
        when(pot.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(pot.getClickedBlock().getWorld()).thenReturn(world);
        when(pot.getClickedBlock().getType()).thenReturn(org.bukkit.Material.FLOWER_POT);
        when(pot.getPlayer()).thenReturn(player);
        var frame = mock(org.bukkit.entity.ItemFrame.class); when(frame.getWorld()).thenReturn(world);
        var interact = mock(PlayerInteractEntityEvent.class);
        when(interact.getRightClicked()).thenReturn(frame); when(interact.getPlayer()).thenReturn(player);
        var hit = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);
        when(hit.getEntity()).thenReturn(frame); when(hit.getDamager()).thenReturn(player);
        var broken = mock(org.bukkit.event.hanging.HangingBreakByEntityEvent.class);
        when(broken.getEntity()).thenReturn(frame); when(broken.getRemover()).thenReturn(player);
        listener.onPlayerInteract(pot); listener.onPlayerInteractEntity(interact); listener.onEntityDamage(hit); listener.onHangingBreak(broken);
        verify(pot).setCancelled(true); verify(interact).setCancelled(true); verify(hit).setCancelled(true); verify(broken).setCancelled(true);
        clearInvocations(pot, interact, hit, broken);
        when(player.hasPermission("contender.admin")).thenReturn(true);
        listener.onPlayerInteract(pot); listener.onPlayerInteractEntity(interact); listener.onEntityDamage(hit); listener.onHangingBreak(broken);
        verify(pot, never()).setCancelled(anyBoolean()); verify(interact, never()).setCancelled(anyBoolean());
        verify(hit, never()).setCancelled(anyBoolean()); verify(broken, never()).setCancelled(anyBoolean());
        Player victim = mock(Player.class); when(victim.getWorld()).thenReturn(world); when(hit.getEntity()).thenReturn(victim);
        listener.onEntityDamage(hit); verify(hit).setCancelled(true);
    }

    @Test void allowedBuildersDoNotUncancelAnotherPluginsProtection() {
        when(player.hasPermission("contender.admin")).thenReturn(true);
        var event = mock(BlockBreakEvent.class, RETURNS_DEEP_STUBS);
        when(event.getBlock().getWorld()).thenReturn(world); when(event.getPlayer()).thenReturn(player);
        when(event.isCancelled()).thenReturn(true);
        listener.onBlockBreak(event);
        assertTrue(event.isCancelled()); verify(event, never()).setCancelled(false);
    }

    @Test void killOnlyBypassesLobbyProtectionForSourceCourseMobs() {
        var races = mock(RaceManager.class);
        when(server.plugin.getRaceManager()).thenReturn(races);
        for (Entity checkpoint : new Entity[]{mock(Pig.class), mock(ArmorStand.class)}) {
            when(checkpoint.getWorld()).thenReturn(world);
            when(races.isCourseMob(checkpoint)).thenReturn(true);
            var kill = damage(checkpoint, EntityDamageEvent.DamageCause.KILL);
            listener.onEntityDamage(kill);
            assertFalse(kill.isCancelled());
        }
        for (Entity other : new Entity[]{mock(Pig.class), player}) {
            when(other.getWorld()).thenReturn(world);
            var kill = damage(other, EntityDamageEvent.DamageCause.KILL);
            listener.onEntityDamage(kill);
            assertTrue(kill.isCancelled());
        }
    }

    @Test void courseMobsRemainProtectedFromOrdinaryDamageInTheLobby() {
        var races = mock(RaceManager.class);
        when(server.plugin.getRaceManager()).thenReturn(races);
        var checkpoint = mock(Pig.class);
        when(checkpoint.getWorld()).thenReturn(world);
        when(races.isCourseMob(checkpoint)).thenReturn(true);
        var hit = damage(checkpoint, EntityDamageEvent.DamageCause.FALL);
        listener.onEntityDamage(hit);
        assertTrue(hit.isCancelled());
    }

    @Test void sourceCourseKillDoesNotUncancelAnotherPluginsProtection() {
        var races = mock(RaceManager.class);
        when(server.plugin.getRaceManager()).thenReturn(races);
        var checkpoint = mock(Pig.class);
        when(checkpoint.getWorld()).thenReturn(world);
        when(races.isCourseMob(checkpoint)).thenReturn(true);
        var kill = damage(checkpoint, EntityDamageEvent.DamageCause.KILL);
        kill.setCancelled(true);
        listener.onEntityDamage(kill);
        assertTrue(kill.isCancelled());
    }

    private EntityDamageEvent damage(Entity entity, EntityDamageEvent.DamageCause cause) {
        return new EntityDamageEvent(entity, cause, mock(DamageSource.class), 10);
    }

    @Test void configurationSelectsSavedLocationForBothNewAndReturningPlayers() throws Exception {
        Location location = new Location(world, 5.5, 80, -10.5, 90, 15);
        doReturn(location).when(lobby).getLobbyLocation();
        when(server.scheduler.callSyncMethod(eq(server.plugin), any())).thenAnswer(call -> {
            Callable<Location> lookup = call.getArgument(1);
            return CompletableFuture.completedFuture(lookup.call());
        });
        var event = mock(AsyncPlayerSpawnLocationEvent.class, RETURNS_DEEP_STUBS);
        UUID id = player.getUniqueId();
        when(event.getConnection().getProfile().getId()).thenReturn(id);
        for (boolean newPlayer : new boolean[]{true, false}) {
            when(event.isNewPlayer()).thenReturn(newPlayer); listener.onSpawnLocation(event);
        }
        verify(event, times(2)).setSpawnLocation(location);
        clearInvocations(event);
        config.set("lobby.teleport-on-join", false); listener.onSpawnLocation(event);
        verify(event, never()).setSpawnLocation(any());
        config.set("lobby.teleport-on-join", true);
        when(duels.getDuel(player.getUniqueId())).thenReturn(mock(Duel.class)); listener.onSpawnLocation(event);
        verify(event, never()).setSpawnLocation(any());
    }

    @Test void delayedJoinFinishesLobbySetupUnlessADuelStartedOrThePlayerDisconnected() {
        doNothing().when(lobby).sendToLobby(player);
        var event = mock(PlayerJoinEvent.class); when(event.getPlayer()).thenReturn(player);
        listener.onPlayerJoin(event); verify(lobby, never()).sendToLobby(player);
        server.scheduled.getLast().run(); verify(lobby).sendToLobby(player); clearInvocations(lobby);
        verify(roles).applySpectatorRole(player);
        listener.onPlayerJoin(event); when(duels.getDuel(player)).thenReturn(mock(Duel.class));
        server.scheduled.getLast().run(); verify(lobby, never()).sendToLobby(player);
        when(duels.getDuel(player)).thenReturn(null); when(player.isOnline()).thenReturn(false);
        listener.onPlayerJoin(event); server.scheduled.getLast().run(); verify(lobby, never()).sendToLobby(player);
    }

    @Test void recoveryFinishingBeforeDelayedJoinDoesNotClearRestoredInventory() {
        var minigames = mock(me.advait.contender.minigame.MinigameManager.class);
        when(server.plugin.getMinigameManager()).thenReturn(minigames);
        when(minigames.pendingReturn(player.getUniqueId())).thenReturn(true);
        var event = mock(PlayerJoinEvent.class); when(event.getPlayer()).thenReturn(player);
        listener.onPlayerJoin(event);
        when(minigames.pendingReturn(player.getUniqueId())).thenReturn(false);
        server.scheduled.getLast().run();
        verify(lobby, never()).sendToLobby(player); verify(roles, never()).applySpectatorRole(player);
    }

    @Test void turningOffJoinTeleportsStillAppliesSpectatorRoles() {
        var roles = mock(RoleManager.class); when(server.plugin.getRoleManager()).thenReturn(roles);
        config.set("lobby.teleport-on-join", false);
        var event = mock(PlayerJoinEvent.class); when(event.getPlayer()).thenReturn(player);
        listener.onPlayerJoin(event); server.scheduled.getLast().run();
        verify(roles).applySpectatorRole(player); verify(lobby, never()).sendToLobby(player);
    }
}
