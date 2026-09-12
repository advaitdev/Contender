package me.advait.contender.tab;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import me.advait.contender.nametag.NameTagManager;
import me.advait.contender.testutil.StateTestServer;
import me.advait.contender.tournament.TournamentManager;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TabManagerTest {
    private static final class Fixture implements AutoCloseable {
        final StateTestServer state = new StateTestServer();
        final TabBridge bridge = mock(TabBridge.class);
        final YamlConfiguration config = new YamlConfiguration();
        final List<Player> online = new ArrayList<>();
        final List<Player> players = new ArrayList<>();
        final Map<UUID, Set<UUID>> listed = new HashMap<>();
        final TabManager manager;
        Fixture(boolean available) {
            when(state.plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
            when(state.plugin.getConfig()).thenReturn(config);
            when(bridge.available()).thenReturn(available);
            var tournament = BracketLayoutTest.tournament(2);
            TournamentManager tournaments = mock(TournamentManager.class);
            when(state.plugin.getTournamentManager()).thenReturn(tournaments);
            when(tournaments.current()).thenReturn(tournament);
            when(tournaments.playing()).thenReturn(Map.of());
            NameTagManager names = mock(NameTagManager.class);
            when(names.displayName(any(UUID.class), anyString())).thenAnswer(call -> Component.text(call.getArgument(1, String.class)));
            when(state.plugin.getNameTagManager()).thenReturn(names);
            for (var entry : tournament.entries()) {
                Player p = mock(Player.class);
                when(p.getUniqueId()).thenReturn(entry.players().getFirst()); when(p.getName()).thenReturn(entry.name());
                when(p.getPing()).thenReturn(30); when(p.canSee(any(Player.class))).thenReturn(true);
                when(p.playerListHeader()).thenReturn(Component.text("Previous header"));
                when(p.playerListFooter()).thenReturn(Component.text("Previous footer"));
                PlayerProfile profile = mock(PlayerProfile.class);
                when(profile.getProperties()).thenReturn(Set.of(new ProfileProperty("textures", "skin", "signed")));
                when(p.getPlayerProfile()).thenReturn(profile);
                online.add(p); players.add(p); listed.put(p.getUniqueId(), new HashSet<>());
            }
            for (Player viewer : players) {
                Set<UUID> set = listed.get(viewer.getUniqueId());
                players.forEach(p -> set.add(p.getUniqueId()));
                when(viewer.isListed(any(Player.class))).thenAnswer(call -> set.contains(call.getArgument(0, Player.class).getUniqueId()));
                doAnswer(call -> { set.remove(call.getArgument(0, Player.class).getUniqueId()); return null; }).when(viewer).unlistPlayer(any(Player.class));
                doAnswer(call -> { set.add(call.getArgument(0, Player.class).getUniqueId()); return null; }).when(viewer).listPlayer(any(Player.class));
            }
            when(state.server.getOnlinePlayers()).thenAnswer(call -> online);
            when(state.server.getPlayer(any(UUID.class))).thenAnswer(call -> online.stream().filter(p -> p.getUniqueId().equals(call.getArgument(0))).findFirst().orElse(null));
            manager = new TabManager(state.plugin, bridge);
        }
        public void close() { manager.disable(); state.close(); }
    }
    @Test void disconnectReconnectKeepsRowAndSkinAndUpdatesPingWithoutDuplicateRealRows() {
        try (var f = new Fixture(true)) {
            f.manager.enable();
            Player viewer = f.players.getFirst(), departing = f.players.getLast();
            f.manager.onQuit(new PlayerQuitEvent(departing, Component.empty())); f.online.remove(departing); f.manager.refresh();
            var offline = f.manager.layout(viewer);
            assertEquals(-1, offline.rows().get(3).latency());
            assertEquals("skin", offline.rows().get(3).texture());
            assertTrue(f.listed.get(viewer.getUniqueId()).isEmpty());
            f.online.add(departing); f.listed.get(viewer.getUniqueId()).add(departing.getUniqueId()); f.manager.refresh();
            assertEquals(30, f.manager.layout(viewer).rows().get(3).latency());
            assertTrue(f.listed.get(viewer.getUniqueId()).isEmpty());
            verify(viewer, never()).hidePlayer(any(), any());
        }
    }
    @Test void disablingRestoresHeaderFooterAndOnlyPlayersOriginallyListed() {
        try (var f = new Fixture(true)) {
            Player viewer = f.players.getFirst(), other = f.players.getLast();
            f.listed.get(viewer.getUniqueId()).remove(other.getUniqueId());
            f.manager.enable(); f.manager.disable();
            assertEquals(Set.of(viewer.getUniqueId()), f.listed.get(viewer.getUniqueId()));
            verify(viewer).sendPlayerListHeaderAndFooter(Component.text("Previous header"), Component.text("Previous footer"));
            verify(f.bridge).clear(viewer);
        }
    }
    @Test void unchangedRowsAreNotResentAndViewsAreIndependent() {
        try (var f = new Fixture(true)) {
            f.manager.enable(); f.manager.refresh();
            for (Player p : f.players) verify(f.bridge, times(1)).show(eq(p), anyList());
            f.manager.setView(f.players.getFirst(), new BracketLayout.View(0, 0, true));
            assertTrue(f.manager.layout(f.players.getFirst()).standings());
            assertFalse(f.manager.layout(f.players.getLast()).standings());
        }
    }
    @Test void stockPaperDoesNotUnlistPlayersOrPushFakeRows() {
        try (var f = new Fixture(false)) {
            f.manager.enable(); f.manager.refresh();
            for (Player p : f.players) verify(p, never()).unlistPlayer(any());
            verify(f.bridge, never()).show(any(), any());
        }
    }
    @Test void failedPushRestoresTheListAndDoesNotRetryEveryTick() {
        try (var f = new Fixture(true)) {
            doThrow(new IllegalStateException("transport failed")).when(f.bridge).show(any(), any());
            f.manager.enable(); f.manager.refresh(); f.manager.refresh();
            verify(f.bridge, times(1)).show(any(), any());
            assertFalse(f.manager.supportsBracket());
            assertEquals(2, f.listed.get(f.players.getFirst().getUniqueId()).size());
        }
    }
    @Test void savedOfflineTeamMemberUsesStoredNameAndCachedSkinBeforeJoining() {
        try (var f = new Fixture(true)) {
            Player viewer = f.players.getFirst(), absent = f.players.getLast();
            var tournament = new me.advait.contender.tournament.Tournament(UUID.randomUUID(), "Teams", "map", "kit",
                    List.of(new me.advait.contender.tournament.TournamentEntry("Red", List.of(viewer.getUniqueId()), Map.of(viewer.getUniqueId(), viewer.getName())),
                            new me.advait.contender.tournament.TournamentEntry("Blue", List.of(absent.getUniqueId()), Map.of(absent.getUniqueId(), "Bob"))),
                    true, false, 3, 10, 2);
            when(f.state.plugin.getTournamentManager().current()).thenReturn(tournament);
            org.bukkit.OfflinePlayer saved = mock(org.bukkit.OfflinePlayer.class);
            PlayerProfile cachedProfile = absent.getPlayerProfile();
            when(saved.getPlayerProfile()).thenReturn(cachedProfile);
            when(f.state.server.getOfflinePlayer(absent.getUniqueId())).thenReturn(saved);
            f.online.remove(absent);
            f.manager.enable();
            var layout = f.manager.layout(viewer);
            assertTrue(f.manager.supportsBracket());
            assertEquals(-1, layout.rows().get(22).latency());
            assertEquals("skin", layout.rows().get(22).texture());
            assertEquals("Bob", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(layout.rows().get(22).text()));
        }
    }
    @Test void restoringWaitsUntilTheViewerCanSeeThePlayerAgain() {
        try (var f = new Fixture(true)) {
            Player viewer = f.players.getFirst(), other = f.players.getLast();
            f.manager.enable();
            when(viewer.canSee(other)).thenReturn(false);
            f.config.set("tablist.enabled", false); f.manager.refresh();
            verify(viewer, never()).listPlayer(other);
            when(viewer.canSee(other)).thenReturn(true); f.manager.refresh();
            verify(viewer).listPlayer(other);
        }
    }
    @Test void cancellationRestoresDefaultTabWhileMatchesAreStillCleaningUp() {
        try (var f = new Fixture(true)) {
            var tournament = f.state.plugin.getTournamentManager().current();
            tournament.matches().getFirst().start();
            f.manager.enable();
            tournament.cancel();
            f.manager.refresh(); f.manager.refresh();
            assertEquals(me.advait.contender.tournament.TournamentMatch.Status.PLAYING, tournament.matches().getFirst().status());
            for (Player player : f.players) {
                verify(f.bridge).clear(player);
                verify(f.bridge, times(1)).show(eq(player), anyList());
                assertEquals(2, f.listed.get(player.getUniqueId()).size());
                verify(player).sendPlayerListHeaderAndFooter(Component.text("Previous header"), Component.text("Previous footer"));
            }
            // A late cancellation result must not bring the bracket back.
            tournament.matches().getFirst().finish(new me.advait.contender.duel.DuelResult(me.advait.contender.duel.DuelResult.Reason.CANCELLED, 0, 0, null));
            f.manager.refresh();
            verify(f.bridge, times(2)).show(any(), anyList());
            var next = new me.advait.contender.tournament.Tournament(UUID.randomUUID(), "Next stage", "map", "kit", tournament.entries(), false, false, 3, 10, 2);
            when(f.state.plugin.getTournamentManager().current()).thenReturn(next);
            f.manager.refresh();
            for (Player player : f.players) verify(f.bridge, times(2)).show(eq(player), anyList());
        }
    }
    @Test void aCancelledTournamentNeverTakesOverTabOnLoginAndRelistsPlayersAfterCleanup() {
        try (var f = new Fixture(true)) {
            var tournament = f.state.plugin.getTournamentManager().current();
            Player viewer = f.players.getFirst(), other = f.players.getLast();
            f.manager.enable(); when(viewer.canSee(other)).thenReturn(false);
            tournament.cancel(); f.manager.refresh();
            verify(viewer, never()).listPlayer(other);
            when(viewer.canSee(other)).thenReturn(true); f.manager.refresh();
            verify(viewer).listPlayer(other);
        }
        try (var f = new Fixture(true)) {
            f.state.plugin.getTournamentManager().current().cancel();
            f.manager.enable(); f.manager.refresh();
            verify(f.bridge, never()).show(any(), anyList());
            for (Player player : f.players) verify(player, never()).unlistPlayer(any());
        }
    }
    @Test void selectedMinigameOverridesStaleRaceAndRoundRobinAndCancellationRestoresTab() {
        try (var f = new Fixture(true)) {
            var coordinator = mock(me.advait.contender.minigame.MinigameManager.class);
            var mode = mock(me.advait.contender.minigame.MinigameMode.class);
            when(f.state.plugin.getMinigameManager()).thenReturn(coordinator); when(coordinator.selected()).thenReturn(mode);
            when(mode.id()).thenReturn("combo"); when(mode.displayName()).thenReturn("Combo");
            when(mode.eventId()).thenReturn(UUID.randomUUID()); when(mode.eventName()).thenReturn("Combo Stage"); when(mode.statusText()).thenReturn("Playing");
            var standings = f.players.stream().map(player -> new me.advait.contender.minigame.MinigameStanding(
                    player.getUniqueId(), player.getName(), "∞", net.kyori.adventure.text.format.NamedTextColor.GREEN)).toList();
            when(mode.standings()).thenReturn(standings);
            var races = mock(me.advait.contender.race.RaceManager.class); when(f.state.plugin.getRaceManager()).thenReturn(races);
            var stale = new me.advait.contender.race.RaceRun(UUID.randomUUID(), "Old Race", "map", 15, 0,
                    List.of(new me.advait.contender.race.RaceRun.Racer(UUID.randomUUID(), "OldPlayer")));
            when(races.displayed()).thenReturn(stale);

            f.manager.enable();
            assertEquals("Combo Stage", f.manager.event().name()); assertTrue(f.manager.event().minigame());
            assertEquals("Combo", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(f.manager.layout(f.players.getFirst()).rows().getFirst().text()));
            when(mode.cancelled()).thenReturn(true); f.manager.refresh(); f.manager.refresh();

            assertNull(f.manager.layout(f.players.getFirst()));
            for (Player player : f.players) {
                verify(f.bridge).clear(player); verify(f.bridge, times(1)).show(eq(player), anyList());
                assertEquals(2, f.listed.get(player.getUniqueId()).size());
            }
            verify(races, never()).displayed();
            when(coordinator.selected()).thenReturn(null); f.manager.refresh();
            assertFalse(f.manager.event().minigame());
            for (Player player : f.players) verify(f.bridge, times(2)).show(eq(player), anyList());
            verify(races, never()).displayed();
        }
    }

    @Test void coordinatorSelectedRaceKeepsTimedRowsEvenWhenLegacySelectionIsClear() {
        try (var f = new Fixture(true)) {
            var coordinator = mock(me.advait.contender.minigame.MinigameManager.class);
            var mode = mock(me.advait.contender.minigame.MinigameMode.class);
            var races = mock(me.advait.contender.race.RaceManager.class);
            when(f.state.plugin.getMinigameManager()).thenReturn(coordinator); when(coordinator.selected()).thenReturn(mode);
            when(f.state.plugin.getRaceManager()).thenReturn(races);
            var run = new me.advait.contender.race.RaceRun(UUID.randomUUID(), "Timed Stage", "course", 15, 0,
                    f.players.stream().map(player -> new me.advait.contender.race.RaceRun.Racer(player.getUniqueId(), player.getName())).toList());
            run.countdown(1); run.start(0); run.hit(f.players.getFirst().getUniqueId(), 2, 1_234_000_000L);
            when(races.current()).thenReturn(run); when(mode.id()).thenReturn("mace_race");
            when(mode.eventId()).thenReturn(run.id()); when(mode.eventName()).thenReturn(run.name()); when(mode.statusText()).thenReturn("Racing");

            f.manager.enable();

            assertEquals("Finish Times", f.manager.event().caption());
            assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(f.manager.layout(f.players.getFirst()).rows().get(2).text()).endsWith("0:01.234"));
            verify(races, never()).displayed(); verify(mode, never()).standings();
        }
    }

    @Test void configOffRestoresRows() {
        try (var f = new Fixture(true)) {
            f.manager.enable();
            f.config.set("tablist.enabled", false); f.manager.refresh();
            for (Player p : f.players) assertEquals(2, f.listed.get(p.getUniqueId()).size());
            verify(f.bridge, times(2)).clear(any());
        }
    }
    @Test void footersShowOnlyTheCurrentViewAndTournamentStatus() {
        try (var f = new Fixture(true)) {
            f.manager.enable();
            Player viewer = f.players.getFirst();
            verify(viewer).sendPlayerListFooter(Component.text("Round 1 | Paused", net.kyori.adventure.text.format.NamedTextColor.GRAY));
            f.manager.setView(viewer, new BracketLayout.View(0, 0, true));
            verify(viewer).sendPlayerListFooter(Component.text("Standings | Paused", net.kyori.adventure.text.format.NamedTextColor.GRAY));
        }
        try (var f = new Fixture(false)) {
            f.manager.enable();
            verify(f.players.getFirst()).sendPlayerListFooter(Component.text("Paused", net.kyori.adventure.text.format.NamedTextColor.GRAY));
        }
    }
}
