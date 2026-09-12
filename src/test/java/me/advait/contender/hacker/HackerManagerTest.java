package me.advait.contender.hacker;

import me.advait.contender.duel.Duel;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.minigame.MinigameManager;
import me.advait.contender.role.RoleManager;
import me.advait.contender.testutil.StateTestServer;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HackerManagerTest {
    @TempDir Path folder;
    private Player player(String name) {
        Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name); when(player.isOnline()).thenReturn(true); return player;
    }
    private HackerManager manager(StateTestServer server) {
        when(server.plugin.getDataFolder()).thenReturn(folder.toFile());
        when(server.plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        var roles = mock(RoleManager.class); when(roles.isContestant(any())).thenReturn(true);
        when(server.plugin.getRoleManager()).thenReturn(roles);
        when(server.plugin.getDuelManager()).thenReturn(mock(DuelManager.class));
        return new HackerManager(server.plugin);
    }
    @Test void hackersGetPrivateRedTitlesAndEveryoneElseGetsGreen() {
        try (var server = new StateTestServer(); var attributes = mockStatic(HackerAttributes.class)) {
            var manager = manager(server); Player alice = player("Alice"), bob = player("Bob"), viewer = player("Viewer");
            when(server.plugin.getRoleManager().isContestant(viewer.getUniqueId())).thenReturn(false);
            doReturn(List.of(alice, bob, viewer)).when(server.server).getOnlinePlayers();
            manager.pick(List.of(alice, bob));
            var title = ArgumentCaptor.forClass(Title.class);
            verify(alice).showTitle(title.capture());
            assertEquals("You are the hacker.", PlainTextComponentSerializer.plainText().serialize(title.getValue().title()));
            assertEquals("Your teammates: Bob", PlainTextComponentSerializer.plainText().serialize(title.getValue().subtitle()));
            assertEquals(NamedTextColor.RED, title.getValue().title().color());
            assertEquals(NamedTextColor.RED, title.getValue().subtitle().color());
            verify(bob).showTitle(title.capture());
            assertEquals("Your teammates: Alice", PlainTextComponentSerializer.plainText().serialize(title.getValue().subtitle()));
            verify(viewer).showTitle(title.capture());
            assertEquals("You are not the hacker.", PlainTextComponentSerializer.plainText().serialize(title.getValue().title()));
            assertEquals(NamedTextColor.GREEN, title.getValue().title().color());
            assertEquals("", PlainTextComponentSerializer.plainText().serialize(title.getValue().subtitle()));
            clearInvocations(alice, bob, viewer); manager.pick(List.of(alice)); verify(alice).showTitle(title.capture());
            assertEquals("", PlainTextComponentSerializer.plainText().serialize(title.getValue().subtitle()));
            assertEquals(NamedTextColor.RED, title.getValue().title().color());
            verify(bob).showTitle(title.capture());
            assertEquals("You are not the hacker.", PlainTextComponentSerializer.plainText().serialize(title.getValue().title()));
            assertEquals(NamedTextColor.GREEN, title.getValue().title().color());
            assertFalse(manager.isHacker(bob.getUniqueId()));
            clearInvocations(alice, bob, viewer); manager.pick(List.of());
            for (Player player : List.of(alice, bob, viewer)) verify(player, never()).showTitle(any(Title.class));
        }
    }
    @Test void selectionsPersistAndOfflineHackersGetTheirTitleOnJoinOnlyOnce() {
        try (var server = new StateTestServer(); var attributes = mockStatic(HackerAttributes.class)) {
            var manager = manager(server); Player alice = player("Alice"); manager.pick(List.of(alice));
            var settings = HackSettings.defaults().with(Map.of(HackSetting.REACH, 3.25));
            manager.update(alice, manager.profile(alice.getUniqueId()).selection(), settings);
            var loaded = new HackerManager(server.plugin);
            assertEquals(settings, loaded.profile(alice.getUniqueId()).settings());
            loaded.enable(); doReturn(List.of(alice)).when(server.server).getOnlinePlayers();
            loaded.onJoin(new PlayerJoinEvent(alice, (net.kyori.adventure.text.Component) null)); server.scheduled.getLast().run();
            loaded.onJoin(new PlayerJoinEvent(alice, (net.kyori.adventure.text.Component) null)); server.scheduled.getLast().run();
            verify(alice).showTitle(any(Title.class)); loaded.disable();
        }
    }
    @Test void invalidRostersAreAtomicAndOldSelectionsCannotChangeSettings() {
        try (var server = new StateTestServer(); var attributes = mockStatic(HackerAttributes.class)) {
            var manager = manager(server); Player alice = player("Alice"), bob = player("Bob"); manager.pick(List.of(alice));
            UUID token = manager.profile(alice.getUniqueId()).selection();
            when(server.plugin.getRoleManager().isContestant(bob.getUniqueId())).thenReturn(false);
            assertThrows(IllegalArgumentException.class, () -> manager.pick(List.of(alice, bob)));
            assertTrue(manager.isHacker(alice.getUniqueId()));
            assertThrows(IllegalArgumentException.class, () -> manager.pick(List.of(alice, alice)));
            manager.pick(List.of()); assertFalse(manager.isHacker(alice.getUniqueId()));
            assertThrows(IllegalStateException.class, () -> manager.update(alice, token, HackSettings.defaults()));
            manager.pick(List.of(alice));
            assertThrows(IllegalStateException.class, () -> manager.update(alice, token, HackSettings.defaults()));
        }
    }
    @Test void abilitiesStayOnInTheLobbyCountdownSpectatingAndAfterCancellation() {
        try (var server = new StateTestServer(); var attributes = mockStatic(HackerAttributes.class)) {
            var manager = manager(server); Player alice = player("Alice");
            doReturn(List.of(alice)).when(server.server).getOnlinePlayers(); manager.pick(List.of(alice)); manager.enable();
            var settings = HackSettings.defaults().with(Map.of(HackSetting.ATTACK_SPEED, 1.4));
            manager.update(alice, manager.profile(alice.getUniqueId()).selection(), settings);
            attributes.clearInvocations();
            assertTrue(manager.hasActiveHacks(alice));
            var duel = mock(Duel.class); when(server.plugin.getDuelManager().getDuel(alice)).thenReturn(duel);
            when(duel.isInDuel(alice.getUniqueId())).thenReturn(true);
            assertTrue(manager.hasActiveHacks(alice));
            when(duel.isCombatActive()).thenReturn(true); manager.refresh();
            assertTrue(manager.hasActiveHacks(alice)); attributes.verify(() -> HackerAttributes.apply(alice, settings));
            when(duel.isSpectator(alice.getUniqueId())).thenReturn(true); manager.refresh(); assertTrue(manager.hasActiveHacks(alice));
            when(duel.isSpectator(alice.getUniqueId())).thenReturn(false); manager.refresh(); assertTrue(manager.hasActiveHacks(alice));
            when(duel.isCombatActive()).thenReturn(false); manager.refresh(); assertTrue(manager.hasActiveHacks(alice));
            when(server.plugin.getDuelManager().getDuel(alice)).thenReturn(null); manager.refresh();
            assertTrue(manager.hasActiveHacks(alice));
            assertEquals(settings, manager.profile(alice.getUniqueId()).settings());
            attributes.verify(() -> HackerAttributes.apply(alice, null), never());
            manager.disable(); attributes.verify(() -> HackerAttributes.apply(alice, null), atLeastOnce());
        }
    }
    @Test void changingSettingsDuringAnActiveDuelAppliesWithoutWaitingForAScheduledRefresh() {
        try (var server = new StateTestServer(); var attributes = mockStatic(HackerAttributes.class)) {
            var manager = manager(server); Player alice = player("Alice");
            doReturn(List.of(alice)).when(server.server).getOnlinePlayers();
            manager.pick(List.of(alice)); manager.enable();
            var duel = mock(Duel.class);
            when(server.plugin.getDuelManager().getDuel(alice)).thenReturn(duel);
            when(duel.isInDuel(alice.getUniqueId())).thenReturn(true);
            when(duel.isCombatActive()).thenReturn(true);
            attributes.clearInvocations();

            var changed = HackSettings.defaults().with(Map.of(HackSetting.REACH, 4.0, HackSetting.ATTACK_SPEED, 2.0));
            manager.update(alice, manager.profile(alice.getUniqueId()).selection(), changed);

            attributes.verify(() -> HackerAttributes.apply(alice, changed));
            attributes.verifyNoMoreInteractions();
            assertEquals(changed, new HackerManager(server.plugin).profile(alice.getUniqueId()).settings());
            manager.disable();
        }
    }
    @Test void changingSettingsDuringAnActiveMinigameAppliesWithoutADuel() {
        try (var server = new StateTestServer(); var attributes = mockStatic(HackerAttributes.class)) {
            var manager = manager(server); Player alice = player("Alice");
            doReturn(List.of(alice)).when(server.server).getOnlinePlayers();
            manager.pick(List.of(alice)); manager.enable();
            var minigames = mock(MinigameManager.class);
            when(server.plugin.getMinigameManager()).thenReturn(minigames);
            when(minigames.isPlaying(alice.getUniqueId())).thenReturn(true);
            attributes.clearInvocations();

            var changed = HackSettings.defaults().with(Map.of(HackSetting.MOVEMENT_SPEED, 1.5, HackSetting.JUMP_STRENGTH, 2.0));
            manager.update(alice, manager.profile(alice.getUniqueId()).selection(), changed);

            attributes.verify(() -> HackerAttributes.apply(alice, changed));
            attributes.verifyNoMoreInteractions();
            assertEquals(changed, new HackerManager(server.plugin).profile(alice.getUniqueId()).settings());
            manager.disable();
        }
    }
    @Test void lobbyChangesApplyImmediatelyAndStayAppliedWhenCombatStarts() {
        try (var server = new StateTestServer(); var attributes = mockStatic(HackerAttributes.class)) {
            var manager = manager(server); Player alice = player("Alice");
            doReturn(List.of(alice)).when(server.server).getOnlinePlayers();
            manager.pick(List.of(alice)); manager.enable();
            attributes.clearInvocations();

            var changed = HackSettings.defaults().with(Map.of(HackSetting.REACH, 5.0, HackSetting.ANTI_KNOCKBACK, 50.0));
            manager.update(alice, manager.profile(alice.getUniqueId()).selection(), changed);

            assertTrue(manager.hasActiveHacks(alice));
            attributes.verify(() -> HackerAttributes.apply(alice, changed));
            attributes.verifyNoMoreInteractions();
            assertEquals(changed, new HackerManager(server.plugin).profile(alice.getUniqueId()).settings());
            attributes.clearInvocations();

            var duel = mock(Duel.class);
            when(server.plugin.getDuelManager().getDuel(alice)).thenReturn(duel);
            when(duel.isInDuel(alice.getUniqueId())).thenReturn(true);
            when(duel.isCombatActive()).thenReturn(true);
            server.scheduled.getLast().run();

            attributes.verify(() -> HackerAttributes.apply(alice, changed));
            attributes.verifyNoMoreInteractions();
            manager.disable();
        }
    }
    @Test void resistanceWorksInTheLobbyAndWhileWatchingButExcludesVoidAndUnselectedPlayers() {
        try (var server = new StateTestServer(); var attributes = mockStatic(HackerAttributes.class)) {
            var manager = manager(server); Player alice = player("Alice"); manager.pick(List.of(alice)); manager.enable();
            manager.update(alice, manager.profile(alice.getUniqueId()).selection(), HackSettings.defaults().with(Map.of(HackSetting.RESISTANCE, 100.0)));
            var event = mock(EntityDamageEvent.class); when(event.getEntity()).thenReturn(alice); when(event.getDamage()).thenReturn(10.0);
            when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.VOID); manager.onDamage(event); verify(event, never()).setDamage(anyDouble());
            when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL); manager.onDamage(event); verify(event).setDamage(0);
            var duel = mock(Duel.class); when(server.plugin.getDuelManager().getDuel(alice)).thenReturn(duel);
            clearInvocations(event); when(duel.isSpectator(alice.getUniqueId())).thenReturn(true); manager.onDamage(event); verify(event).setDamage(0);
            clearInvocations(event); manager.pick(List.of()); manager.onDamage(event); verify(event, never()).setDamage(anyDouble());
            manager.disable();
        }
    }

    @Test void lobbySettingsReturnAfterReconnectAndRespawn() {
        try (var server = new StateTestServer(); var attributes = mockStatic(HackerAttributes.class)) {
            var manager = manager(server); Player alice = player("Alice");
            doReturn(List.of(alice)).when(server.server).getOnlinePlayers();
            manager.enable(); manager.pick(List.of(alice));
            var settings = HackSettings.defaults().with(Map.of(HackSetting.MOVEMENT_SPEED, 2.0));
            manager.update(alice, manager.profile(alice.getUniqueId()).selection(), settings);
            attributes.clearInvocations();

            var quit = mock(org.bukkit.event.player.PlayerQuitEvent.class);
            when(quit.getPlayer()).thenReturn(alice);
            manager.onQuit(quit);
            attributes.verify(() -> HackerAttributes.apply(alice, null));
            attributes.clearInvocations();

            manager.onJoin(new PlayerJoinEvent(alice, (net.kyori.adventure.text.Component) null));
            server.scheduled.getLast().run();
            attributes.verify(() -> HackerAttributes.apply(alice, settings));
            attributes.clearInvocations();

            manager.onRespawn(mock(org.bukkit.event.player.PlayerRespawnEvent.class));
            server.scheduled.getLast().run();
            attributes.verify(() -> HackerAttributes.apply(alice, settings));
            assertEquals(settings, manager.profile(alice.getUniqueId()).settings());
            manager.disable();
        }
    }

    @Test void roleChangesAndClearingSelectionRemoveLobbyAbilities() {
        try (var server = new StateTestServer(); var attributes = mockStatic(HackerAttributes.class)) {
            var manager = manager(server); Player alice = player("Alice");
            doReturn(List.of(alice)).when(server.server).getOnlinePlayers();
            manager.enable(); manager.pick(List.of(alice));
            UUID selection = manager.profile(alice.getUniqueId()).selection();
            var settings = HackSettings.defaults().with(Map.of(HackSetting.REACH, 6.0));
            manager.update(alice, selection, settings);
            attributes.clearInvocations();

            when(server.plugin.getRoleManager().isContestant(alice.getUniqueId())).thenReturn(false);
            manager.refresh();
            assertFalse(manager.hasActiveHacks(alice));
            attributes.verify(() -> HackerAttributes.apply(alice, null));
            assertThrows(IllegalStateException.class, () -> manager.update(alice, selection, settings));

            when(server.plugin.getRoleManager().isContestant(alice.getUniqueId())).thenReturn(true);
            manager.refresh();
            attributes.verify(() -> HackerAttributes.apply(alice, settings));
            attributes.clearInvocations();

            manager.pick(List.of());
            assertFalse(manager.hasActiveHacks(alice));
            attributes.verify(() -> HackerAttributes.apply(alice, null));
            assertThrows(IllegalStateException.class, () -> manager.update(alice, selection, settings));
            manager.disable();
        }
    }
}
