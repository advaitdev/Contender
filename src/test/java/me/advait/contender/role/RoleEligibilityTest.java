package me.advait.contender.role;

import me.advait.contender.Contender;
import me.advait.contender.arena.ArenaManager;
import me.advait.contender.duel.*;
import me.advait.contender.kit.*;
import me.advait.contender.map.*;
import me.advait.contender.spectator.SpectatorManager;
import me.advait.contender.tournament.*;
import me.advait.contender.vote.VoteManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoleEligibilityTest {
    @TempDir Path folder;
    private final Contender plugin = mock(Contender.class);
    private final RoleManager roles = mock(RoleManager.class);
    private final ArenaManager arenas = mock(ArenaManager.class);
    private final SpectatorManager spectators = mock(SpectatorManager.class);
    private final List<Player> players = new ArrayList<>();
    private DuelManager manager() {
        when(plugin.getRoleManager()).thenReturn(roles);
        when(plugin.getArenaManager()).thenReturn(arenas);
        when(plugin.getVoteManager()).thenReturn(mock(VoteManager.class));
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        for (int i = 0; i < 4; i++) {
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getName()).thenReturn("Player" + i);
            when(roles.isContestant(player.getUniqueId())).thenReturn(i < 2);
            players.add(player);
        }
        DuelManager manager = new DuelManager(plugin, spectators);
        when(plugin.getDuelManager()).thenReturn(manager);
        return manager;
    }
    private DuelSetup setup() {
        DuelSetup setup = new DuelSetup(UUID.randomUUID());
        setup.setSelectedKit(mock(Kit.class));
        setup.setSelectedMap(new ArenaMap("forest"));
        return setup;
    }
    @Test void manualDuelsRecheckRolesBeforeReservingAnArena() {
        DuelManager manager = manager();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(call -> players.stream().filter(p -> p.getUniqueId().equals(call.getArgument(0))).findFirst().orElse(null));
            DuelSetup setup = setup();
            setup.getTeam1().addPlayer(players.get(0).getUniqueId());
            for (Player nonContestant : players.subList(2, 4)) {
                setup.getTeam2().clearPlayers();
                setup.getTeam2().addPlayer(nonContestant.getUniqueId());
                assertThrows(IllegalArgumentException.class, () -> manager.startDuel(setup));
            }
            verify(arenas, never()).acquire(anyString());
            assertTrue(manager.getActiveDuels().isEmpty());
        }
    }
    @Test void freeForAllDoesNotEnrollDirectorsOrSpectators() {
        DuelManager manager = manager();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(players);
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(call -> players.stream().filter(p -> p.getUniqueId().equals(call.getArgument(0))).findFirst().orElse(null));
            DuelSetup setup = setup();
            setup.setMode(DuelMode.FFA);
            assertThrows(IllegalStateException.class, () -> manager.startDuel(setup)); // No ready arena in this fixture.
            assertEquals(Set.of(players.get(0).getUniqueId(), players.get(1).getUniqueId()), setup.getAllPlayers());
            verify(arenas).acquire("forest");
        }
    }
    @Test void roundRobinRejectsNonContestantsEvenWhenBypassingTheDialog() {
        manager();
        MapManager maps = mock(MapManager.class);
        KitManager kits = mock(KitManager.class);
        when(plugin.getMapManager()).thenReturn(maps);
        when(plugin.getKitManager()).thenReturn(kits);
        when(maps.getMap("forest")).thenReturn(new ArenaMap("forest"));
        when(kits.getKit("sword")).thenReturn(mock(Kit.class));
        TournamentManager tournaments = new TournamentManager(plugin);
        try (var bukkit = mockStatic(Bukkit.class)) {
            for (boolean teamMode : List.of(false, true)) {
                var entries = List.of(new TournamentEntry("A", List.of(players.get(0).getUniqueId())),
                        new TournamentEntry("B", List.of(players.get(2).getUniqueId())));
                Tournament tournament = new Tournament(UUID.randomUUID(), "Test", "forest", "sword", entries, teamMode, false, 3, 10, 20);
                assertThrows(IllegalArgumentException.class, () -> tournaments.create(tournament));
                assertNull(tournaments.current());
            }
            assertFalse(folder.resolve("tournament.yml").toFile().exists());
        }
    }

    @Test void offlineContestantsCanSaveAndResumeABracketWithoutStartingDuels() {
        manager();
        MapManager maps = mock(MapManager.class);
        KitManager kits = mock(KitManager.class);
        when(plugin.getMapManager()).thenReturn(maps);
        when(plugin.getKitManager()).thenReturn(kits);
        when(maps.getMap("forest")).thenReturn(new ArenaMap("forest"));
        when(kits.getKit("sword")).thenReturn(mock(Kit.class));
        when(arenas.available("forest")).thenReturn(20);
        try (var bukkit = mockStatic(Bukkit.class)) {
            var entries = List.of(new TournamentEntry("Alice", List.of(players.get(0).getUniqueId())),
                    new TournamentEntry("Bob", List.of(players.get(1).getUniqueId())));
            Tournament tournament = new Tournament(UUID.randomUUID(), "Friday", "forest", "sword", entries, false, false, 3, 10, 20);
            TournamentManager tournaments = new TournamentManager(plugin);
            tournaments.create(tournament);
            assertFalse(tournaments.current().isRunning());
            assertEquals(entries, new TournamentStore(folder.resolve("tournament.yml").toFile()).load().entries());

            tournaments.resume();
            assertTrue(tournament.isRunning());
            assertTrue(tournaments.playing().isEmpty());
            assertEquals(TournamentMatch.Status.WAITING, tournament.matches().getFirst().status());
            verify(arenas, never()).acquire(anyString());

            tournaments.pause();
            when(spectators.isDeceased(players.get(0).getUniqueId())).thenReturn(true);
            assertThrows(IllegalArgumentException.class, tournaments::resume);
            assertFalse(tournament.isRunning());
        }
    }
}
