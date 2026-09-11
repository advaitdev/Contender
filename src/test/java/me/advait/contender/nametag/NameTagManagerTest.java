package me.advait.contender.nametag;

import me.advait.contender.role.*;
import me.advait.contender.testutil.StateTestServer;
import me.advait.contender.tier.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NameTagManagerTest {
    private static final class Board {
        final Scoreboard board = mock(Scoreboard.class);
        final Map<String, Team> teams = new HashMap<>(), entries = new HashMap<>();
        Board() {
            when(board.getTeam(anyString())).thenAnswer(call -> teams.get(call.getArgument(0)));
            when(board.getEntryTeam(anyString())).thenAnswer(call -> entries.get(call.getArgument(0)));
            when(board.registerNewTeam(anyString())).thenAnswer(call -> create(call.getArgument(0)));
        }
        Team create(String name) {
            Team team = mock(Team.class);
            AtomicBoolean registered = new AtomicBoolean(true);
            AtomicReference<Component> prefix = new AtomicReference<>(Component.empty());
            AtomicReference<NamedTextColor> color = new AtomicReference<>();
            when(team.getName()).thenReturn(name);
            when(team.getScoreboard()).thenAnswer(call -> registered.get() ? board : null);
            when(team.prefix()).thenAnswer(call -> prefix.get());
            when(team.suffix()).thenReturn(Component.empty());
            when(team.getOption(any())).thenReturn(Team.OptionStatus.ALWAYS);
            when(team.color()).thenAnswer(call -> color.get());
            when(team.hasColor()).thenAnswer(call -> color.get() != null);
            doAnswer(call -> { prefix.set(call.getArgument(0)); return null; }).when(team).prefix(any());
            doAnswer(call -> { color.set(call.getArgument(0)); return null; }).when(team).color(any());
            doAnswer(call -> { entries.put(call.getArgument(0), team); return null; }).when(team).addEntry(anyString());
            doAnswer(call -> {
                entries.values().removeIf(value -> value == team);
                teams.remove(name);
                registered.set(false);
                return null;
            }).when(team).unregister();
            teams.put(name, team);
            return team;
        }
    }
    @Test void updatesVisibleScoreboardsWithoutReplacingThemAndRestoresPreviousMembership() {
        try (var server = new StateTestServer(); var bukkit = mockStatic(Bukkit.class)) {
            Board first = new Board(), second = new Board();
            Player alice = mock(Player.class), bob = mock(Player.class);
            UUID aliceId = UUID.randomUUID(), bobId = UUID.randomUUID();
            when(alice.getUniqueId()).thenReturn(aliceId); when(alice.getName()).thenReturn("Alice");
            when(bob.getUniqueId()).thenReturn(bobId); when(bob.getName()).thenReturn("Bob");
            when(alice.getScoreboard()).thenReturn(first.board); when(bob.getScoreboard()).thenReturn(second.board);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(alice, bob));
            RoleManager roles = mock(RoleManager.class);
            when(roles.getRole(any())).thenReturn(PlayerRole.CONTESTANT);
            when(server.plugin.getRoleManager()).thenReturn(roles);
            YamlConfiguration config = new YamlConfiguration();
            when(server.plugin.getConfig()).thenReturn(config);
            PlayerData data = new PlayerData(aliceId.toString(), "Alice", "NA", Map.of("sword", new Ranking(1, 0, null, null, true, 0)));
            TierService tiers = mock(TierService.class);
            when(server.plugin.getTierService()).thenReturn(tiers);
            when(tiers.cached(aliceId)).thenReturn(data);
            when(tiers.refresh(any())).thenReturn(CompletableFuture.completedFuture(null));
            Team previous = first.create("previous"); previous.addEntry("Alice");
            NameTagManager names = new NameTagManager(server.plugin);
            names.enable();
            assertEquals(TierFormatter.prefix(data), first.board.getEntryTeam("Alice").prefix());
            assertEquals(TierFormatter.prefix(data), second.board.getEntryTeam("Alice").prefix());
            when(roles.getRole(aliceId)).thenReturn(PlayerRole.DIRECTOR);
            RoleStyle style = new RoleStyle("[Camera]", NamedTextColor.LIGHT_PURPLE);
            style.write(config, PlayerRole.DIRECTOR);
            names.refresh();
            assertEquals(style.prefixComponent(), first.board.getEntryTeam("Alice").prefix());
            assertEquals(style.color(), second.board.getEntryTeam("Alice").color());
            verify(alice, never()).setScoreboard(any()); verify(bob, never()).setScoreboard(any());
            names.disable();
            assertSame(previous, first.board.getEntryTeam("Alice"));
            assertEquals(Set.of("previous"), first.teams.keySet());
            assertTrue(second.teams.isEmpty());
        }
    }
}
