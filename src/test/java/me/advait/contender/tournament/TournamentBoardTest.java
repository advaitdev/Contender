package me.advait.contender.tournament;

import me.advait.contender.duel.Duel;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.tab.*;
import me.advait.contender.testutil.StateTestServer;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class TournamentBoardTest {
    @Test void minigameCancellationRemovesTheBoardAndTheNextEventReusesItsAnchor() {
        try (var env = new StateTestServer(); var bukkit = mockStatic(Bukkit.class)) {
            World world = mock(World.class); when(world.getName()).thenReturn("lobby");
            bukkit.when(() -> Bukkit.getWorld("lobby")).thenReturn(world);
            Chunk chunk = mock(Chunk.class); when(chunk.addPluginChunkTicket(env.plugin)).thenReturn(true);
            when(world.getChunkAt(any(Location.class))).thenReturn(chunk);
            var config = new YamlConfiguration(); config.set("tournament-board.world", "lobby"); config.set("tournament-board.y", 70);
            when(env.plugin.getConfig()).thenReturn(config);
            List<TextDisplay> displays = new ArrayList<>();
            when(world.spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class))).thenAnswer(call -> {
                TextDisplay display = mock(TextDisplay.class); Location location = call.getArgument(0);
                when(display.getLocation()).thenAnswer(ignored -> location.clone());
                ((Consumer<TextDisplay>) call.getArgument(2)).accept(display); displays.add(display); return display;
            });
            var tab = mock(TabManager.class); when(env.plugin.getTabManager()).thenReturn(tab);
            var id = UUID.randomUUID();
            when(tab.event()).thenReturn(new TabManager.Event(id, "Combo Stage", "Playing", "Leaderboard", true, false));
            when(tab.layout(any(BracketLayout.View.class))).thenReturn(new BracketLayout.Layout(
                    List.of(TabRow.label(Component.text("Combo")), TabRow.label(Component.text("Alice  ∞"))), 1, 0, 1, true));
            var board = new TournamentBoard(env.plugin); board.enable(); board.update(null, Map.of());
            assertEquals(28, displays.size());
            for (int i = 22; i < 28; i++) verify(displays.get(i)).text(Component.empty());

            when(tab.event()).thenReturn(new TabManager.Event(id, "Combo Stage", "Cancelled", "Leaderboard", true, true));
            board.update(null, Map.of());
            for (TextDisplay display : displays) verify(display).remove();
            verify(chunk).removePluginChunkTicket(env.plugin);
            assertEquals("lobby", config.getString("tournament-board.world"));

            when(tab.event()).thenReturn(new TabManager.Event(UUID.randomUUID(), "Next Stage", "Ready", "Leaderboard", true, false));
            board.update(null, Map.of());
            assertEquals(56, displays.size());
            board.disable();
        }
    }

    @Test void hoverIsPrivateClickUsesCurrentMatchAndTeardownRestoresTheBaseRow() {
        try (var env = new StateTestServer(); var bukkit = mockStatic(Bukkit.class)) {
            World world = mock(World.class); when(world.getName()).thenReturn("lobby");
            bukkit.when(() -> Bukkit.getWorld("lobby")).thenReturn(world);
            Chunk chunk = mock(Chunk.class); when(chunk.addPluginChunkTicket(env.plugin)).thenReturn(true);
            when(world.getChunkAt(any(Location.class))).thenReturn(chunk);
            YamlConfiguration config = new YamlConfiguration();
            config.set("tournament-board.world", "lobby"); config.set("tournament-board.y", 70); config.set("tournament-board.yaw", 180);
            when(env.plugin.getConfig()).thenReturn(config);
            List<TextDisplay> displays = new ArrayList<>();
            when(world.spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class))).thenAnswer(call -> {
                TextDisplay display = mock(TextDisplay.class); Location location = call.getArgument(0);
                when(display.getLocation()).thenAnswer(ignored -> location.clone());
                ((Consumer<TextDisplay>) call.getArgument(2)).accept(display); displays.add(display); return display;
            });
            Player viewer = mock(Player.class), other = mock(Player.class);
            for (Player player : List.of(viewer, other)) {
                when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.isOnline()).thenReturn(true); when(player.getWorld()).thenReturn(world);
            }
            when(other.getEyeLocation()).thenReturn(new Location(world, 50, 70, -8));
            when(viewer.getEyeLocation()).thenReturn(new Location(world, 50, 70, -8));
            doReturn(List.of(viewer, other)).when(env.server).getOnlinePlayers();
            var manager = mock(TournamentManager.class); when(env.plugin.getTournamentManager()).thenReturn(manager);
            var tab = mock(TabManager.class); when(env.plugin.getTabManager()).thenReturn(tab);
            var duels = mock(DuelManager.class); when(env.plugin.getDuelManager()).thenReturn(duels);
            var tournament = new Tournament(UUID.randomUUID(), "Sword", "map", "kit", List.of(
                    new TournamentEntry("Alice", List.of(UUID.randomUUID())), new TournamentEntry("Bob", List.of(UUID.randomUUID()))), false, false, 3, 10, 20);
            when(manager.current()).thenReturn(tournament);
            when(tab.event()).thenReturn(new TabManager.Event(tournament.id(), tournament.name(), tournament.statusText(), "Bracket", false, false));
            Duel duel = mock(Duel.class); when(manager.playing()).thenReturn(Map.of(1, duel));
            List<TabRow> rows = new ArrayList<>();
            rows.add(TabRow.label(Component.text("Round 1")));
            rows.add(TabRow.label(Component.text("#1 Playing")).match(1));
            while (rows.size() < 20) rows.add(TabRow.label(Component.empty()));
            when(tab.layout(any(BracketLayout.View.class))).thenReturn(new BracketLayout.Layout(rows, 1, 0, 1, false));
            TournamentBoard board = new TournamentBoard(env.plugin); board.enable(); board.update(tournament, manager.playing());
            verify(displays.get(21)).text(Component.text("Round 1", net.kyori.adventure.text.format.NamedTextColor.AQUA));
            for (TextDisplay display : displays) verify(display, atLeastOnce()).setBackgroundColor(Color.fromARGB(0, 16, 22, 29));
            config.set("tournament-board.background-opacity", 90);
            board.update(tournament, manager.playing());
            for (TextDisplay display : displays) verify(display).setBackgroundColor(Color.fromARGB(230, 16, 22, 29));
            TextDisplay row = displays.get(1);
            Location eye = row.getLocation().add(0, .1275, -8); eye.setYaw(0);
            when(viewer.getEyeLocation()).thenReturn(eye);
            env.scheduled.getFirst().run();
            TextDisplay highlight = displays.getLast();
            verify(highlight, atLeastOnce()).setBackgroundColor(Color.fromARGB(230, 16, 22, 29));
            verify(highlight).setVisibleByDefault(false); verify(viewer).showEntity(env.plugin, highlight);
            verify(viewer).hideEntity(env.plugin, row); verify(other, never()).hideEntity(any(), any());
            PlayerAnimationEvent click = new PlayerAnimationEvent(viewer);
            board.click(click); verify(duels).spectate(viewer, duel); assertTrue(click.isCancelled());
            verify(highlight).remove(); verify(viewer).showEntity(env.plugin, row);
            env.scheduled.getFirst().run();
            TextDisplay nextHighlight = displays.getLast();
            board.disable();
            verify(nextHighlight).remove(); verify(viewer, times(2)).showEntity(env.plugin, row);
            verify(chunk).removePluginChunkTicket(env.plugin);
            for (TextDisplay display : displays) verify(display).remove();
        }
    }
}
