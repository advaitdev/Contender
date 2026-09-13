package me.advait.contender.duel;

import me.advait.contender.arena.ArenaInstance;
import me.advait.contender.arena.ArenaLease;
import me.advait.contender.arena.ArenaManager;
import me.advait.contender.kit.Kit;
import me.advait.contender.kit.KitManager;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.MapManager;
import me.advait.contender.role.RoleManager;
import me.advait.contender.spectator.SpectatorVisibility;
import me.advait.contender.testutil.StateTestServer;
import me.advait.contender.tournament.Tournament;
import me.advait.contender.tournament.TournamentBoard;
import me.advait.contender.tournament.TournamentEntry;
import me.advait.contender.tournament.TournamentManager;
import me.advait.contender.tournament.TournamentMatch;
import me.advait.contender.vote.VoteManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DuelDecidedResultTest {
    @TempDir Path folder;

    @ParameterizedTest
    @EnumSource(value = DuelResult.Reason.class, names = {"FINISHED", "FORFEIT"})
    void realDuelReportsItsDecidedScoreBeforeCleanupAndReportsItOnlyOnce(DuelResult.Reason reason) throws Exception {
        try (Fixture f = new Fixture()) {
            List<DuelResult> results = new ArrayList<>();
            Duel duel = f.register(f.setup(), results::add);
            duel.getTeam1().incrementScore(); duel.getTeam1().incrementScore();
            duel.getTeam2().incrementScore();

            if (reason == DuelResult.Reason.FINISHED) duel.endDuel();
            else duel.forfeit(duel.getTeam1().getPlayers().getFirst());

            var result = new DuelResult(reason, 2, 1, reason == DuelResult.Reason.FINISHED ? 1 : 2);
            assertEquals(List.of(result), results, "The score must reach its callback before arena cleanup completes");
            assertInstanceOf(EndingState.class, duel.getState());
            assertFalse(duel.isFinished());
            assertTrue(f.duels.getActiveDuels().contains(duel));
            assertTrue(duel.getAllDuelPlayers().stream().allMatch(f.duels::isPlaying));
            verify(f.arenas, never()).release(duel.getArena());

            duel.endDuel();
            duel.forfeit(duel.getTeam2().getPlayers().getFirst());
            f.duels.reportResult(duel);
            f.finishCleanup(duel);
            f.duels.reportResult(duel);

            assertTrue(duel.isFinished());
            assertEquals(result, duel.getResult());
            assertEquals(List.of(result), results);
            assertFalse(f.duels.getActiveDuels().contains(duel));
            assertTrue(duel.getAllDuelPlayers().stream().noneMatch(f.duels::isPlaying));
            verify(f.arenas, times(1)).release(duel.getArena());
        }
    }

    @ParameterizedTest
    @EnumSource(value = DuelResult.Reason.class, names = {"FINISHED", "FORFEIT"})
    void schedulerWaitsForEndingContestantsEvenAfterTheirResultAdvancesTheStandings(DuelResult.Reason reason) throws Exception {
        try (Fixture f = new Fixture()) {
            f.startTournament();
            f.tournaments.resume();
            assertEquals(1, f.started.size());
            Duel first = f.started.getFirst();
            TournamentMatch match = f.tournament.matches().stream()
                    .filter(candidate -> candidate.status() == TournamentMatch.Status.PLAYING).findFirst().orElseThrow();
            first.getTeam1().incrementScore(); first.getTeam1().incrementScore();

            if (reason == DuelResult.Reason.FINISHED) first.endDuel();
            else first.forfeit(first.getTeam2().getPlayers().getFirst());

            assertEquals(TournamentMatch.Status.FINISHED, match.status());
            assertEquals(reason, match.result().reason());
            assertEquals(2, match.result().team1Score());
            assertTrue(f.tournaments.playing().isEmpty());
            assertTrue(f.tournament.isRunning());
            assertInstanceOf(EndingState.class, first.getState());
            assertTrue(f.freeCopies > 0, "A free copy must not bypass the contestant ownership check");
            assertTrue(first.getAllDuelPlayers().stream().allMatch(f.duels::isPlaying));

            for (int tick = 0; tick < 5; tick++) f.tick.run();

            assertEquals(1, f.started.size(), "Every remaining pairing includes someone whose ending duel still owns them");
            assertTrue(f.tournament.isRunning());
            assertFalse(f.tournaments.waitingReason().contains("unexpectedly"));
            f.finishCleanup(first);
            f.tick.run();

            assertEquals(2, f.started.size());
            assertEquals(1, f.tournament.matches().stream().filter(candidate -> candidate.status() == TournamentMatch.Status.FINISHED).count());
            assertTrue(f.tournament.isRunning());
        }
    }

    private final class Fixture implements AutoCloseable {
        final StateTestServer server = new StateTestServer();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedConstruction<SpectatorVisibility> avatars = mockConstruction(SpectatorVisibility.class);
        final MockedConstruction<TournamentBoard> boards = mockConstruction(TournamentBoard.class);
        final DuelManager duels = spy(new DuelManager(server.plugin));
        final ArenaManager arenas = mock(ArenaManager.class);
        final ArenaMap map = mock(ArenaMap.class);
        final Kit kit = mock(Kit.class);
        final Map<UUID, Player> players = new LinkedHashMap<>();
        final List<Duel> started = new ArrayList<>();
        final Map<Duel, Runnable> cleanups = new HashMap<>();
        int freeCopies = 3;
        Tournament tournament;
        TournamentManager tournaments;
        StateTestServer.Scheduled tick;

        Fixture() {
            when(server.plugin.getDataFolder()).thenReturn(folder.toFile());
            when(server.plugin.getConfig()).thenReturn(new YamlConfiguration());
            when(server.plugin.getLogger()).thenReturn(mock(Logger.class));
            when(server.plugin.getDuelManager()).thenReturn(duels);
            when(server.plugin.getArenaManager()).thenReturn(arenas);
            when(server.plugin.getVoteManager()).thenReturn(mock(VoteManager.class));
            RoleManager roles = mock(RoleManager.class);
            when(server.plugin.getRoleManager()).thenReturn(roles);
            when(roles.isContestant(any())).thenReturn(true);
            when(map.getId()).thenReturn("map");
            when(map.getWorldName()).thenReturn("arenas");
            when(arenas.available("map")).thenAnswer(call -> freeCopies);
            doAnswer(call -> { freeCopies++; return null; }).when(arenas).release(any());
            for (String name : List.of("Alice", "Bob", "Carol")) {
                UUID id = UUID.randomUUID();
                Player player = mock(Player.class);
                when(player.getUniqueId()).thenReturn(id);
                when(player.getName()).thenReturn(name);
                when(player.isOnline()).thenReturn(true);
                players.put(id, player);
            }
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(call -> players.get(call.getArgument(0)));
            bukkit.when(() -> Bukkit.getOfflinePlayer(any(UUID.class))).thenAnswer(call -> players.get(call.getArgument(0)));
            doAnswer(call -> register(call.getArgument(0), call.getArgument(1))).when(duels).startDuel(any(DuelSetup.class), any());
        }

        DuelSetup setup() {
            DuelSetup setup = new DuelSetup(UUID.randomUUID());
            setup.setSelectedMap(map); setup.setSelectedKit(kit); setup.setRounds(3);
            var ids = List.copyOf(players.keySet());
            setup.getTeam1().addPlayer(ids.get(0)); setup.getTeam2().addPlayer(ids.get(1));
            return setup;
        }

        @SuppressWarnings("unchecked") Duel register(DuelSetup setup, Consumer<DuelResult> completion) throws Exception {
            ArenaInstance instance = mock(ArenaInstance.class);
            when(instance.map()).thenReturn(map);
            Duel duel = spy(new Duel(server.plugin, duels, setup, new ArenaLease(instance, UUID.randomUUID()), true));
            // Keep only the gameplay-start and world-paste boundaries out of this result/ownership integration test.
            doReturn(true).when(duel).tryReturnParticipantsToLobby();
            doAnswer(call -> { cleanups.put(duel, call.getArgument(1)); return null; }).when(duel).rollbackRoundArena(any(), any());
            ((List<Duel>) managerField("activeDuels")).add(duel);
            Map<UUID, Duel> ownership = (Map<UUID, Duel>) managerField("playerDuelMap");
            duel.getAllDuelPlayers().forEach(id -> ownership.put(id, duel));
            ((Map<Duel, Consumer<DuelResult>>) managerField("completions")).put(duel, completion);
            freeCopies--;
            started.add(duel);
            return duel;
        }

        private Object managerField(String name) throws Exception {
            var field = DuelManager.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(duels);
        }

        void startTournament() {
            MapManager maps = mock(MapManager.class);
            when(server.plugin.getMapManager()).thenReturn(maps);
            when(maps.getMap("map")).thenReturn(map);
            KitManager kits = mock(KitManager.class);
            when(server.plugin.getKitManager()).thenReturn(kits);
            when(kits.getKit("kit")).thenReturn(kit);
            tournament = new Tournament(UUID.randomUUID(), "Sword", "map", "kit", players.entrySet().stream()
                    .map(entry -> new TournamentEntry(entry.getValue().getName(), List.of(entry.getKey()))).toList(),
                    false, false, 3, 10, 1);
            tournaments = new TournamentManager(server.plugin);
            when(server.plugin.getTournamentManager()).thenReturn(tournaments);
            tournaments.enable();
            tournaments.create(tournament);
            tick = server.scheduled.getLast();
        }

        void finishCleanup(Duel duel) {
            if (!cleanups.containsKey(duel)) server.scheduled.stream()
                    .filter(task -> !task.repeating() && task.delay() == 60L).findFirst().orElseThrow().run();
            assertTrue(cleanups.containsKey(duel));
            cleanups.get(duel).run();
        }

        @Override public void close() {
            started.forEach(duel -> duel.getState().disable());
            if (tournaments != null) tournaments.disable();
            boards.close(); avatars.close(); bukkit.close(); server.close();
        }
    }
}
