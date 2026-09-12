package me.advait.contender.vote;

import me.advait.contender.Contender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.Test;
import java.util.function.Consumer;
import static org.mockito.Mockito.*;

class VoteTimerDisplayTest {
    @Test void timerRemovesItsEntityAndTicketAtZeroButKeepsTheSavedAnchor() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Contender plugin = mock(Contender.class); var config = new YamlConfiguration(); config.set("vote-timer.world", "lobby");
            when(plugin.getConfig()).thenReturn(config); World world = mock(World.class); bukkit.when(() -> Bukkit.getWorld("lobby")).thenReturn(world);
            Chunk chunk = mock(Chunk.class); when(world.getChunkAt(any(Location.class))).thenReturn(chunk); when(chunk.addPluginChunkTicket(plugin)).thenReturn(true);
            TextDisplay display = mock(TextDisplay.class); when(display.isValid()).thenReturn(true);
            when(world.spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class))).thenAnswer(call -> { ((Consumer<TextDisplay>) call.getArgument(2)).accept(display); return display; });
            var timer = new VoteTimerDisplay(plugin); timer.update(65); timer.update(5);
            verify(display).text(argThat(c -> PlainTextComponentSerializer.plainText().serialize(c).equals("Vote\n01:05")));
            verify(display).text(argThat(c -> PlainTextComponentSerializer.plainText().serialize(c).equals("Vote\n00:05")));
            timer.update(0); timer.hide(); verify(display, times(1)).remove(); verify(chunk, times(1)).removePluginChunkTicket(plugin);
            org.junit.jupiter.api.Assertions.assertEquals("lobby", config.getString("vote-timer.world"));
        }
    }
}
