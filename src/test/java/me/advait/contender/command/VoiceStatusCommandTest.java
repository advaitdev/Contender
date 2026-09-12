package me.advait.contender.command;

import me.advait.contender.Contender;
import me.advait.contender.voice.VoiceDiagnostics;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoiceStatusCommandTest {
    @Test void authorizedStaffCanInspectAnExactOnlinePlayerWithoutChangingTheirSettings() {
        Fixture f = new Fixture();
        CommandSender sender = f.staff();
        Player target = f.player("Alice");
        when(f.server.getPlayerExact("Alice")).thenReturn(target);
        when(target.hasPermission("voicechat.speak")).thenReturn(true);
        when(target.hasPermission("voicechat.groups")).thenReturn(true);
        when(f.diagnostics.describe(target.getUniqueId())).thenReturn(List.of("Connected: Yes", "Audience: Spectators"));

        f.run(sender, "voice", "Alice");

        assertEquals(List.of("Voice chat for Alice", "Connected: Yes", "Audience: Spectators",
                "Simple Voice Chat permissions:", "Speak: Allowed · Listen: Blocked · Groups: Allowed"), messages(sender));
        verify(f.server).getPlayerExact("Alice");
        verify(f.diagnostics).describe(target.getUniqueId());
        verifyNoMoreInteractions(f.diagnostics);
        verify(target, never()).sendMessage(any(Component.class));
        verify(f.plugin, never()).reloadConfig();
        verify(f.plugin, never()).getDuelManager();
    }

    @Test void staffPlayersCanInspectThemselvesWithoutTypingTheirName() {
        Fixture f = new Fixture();
        Player sender = f.player("Director");
        when(sender.hasPermission("contender.master")).thenReturn(true);
        when(f.diagnostics.describe(sender.getUniqueId())).thenReturn(List.of("Connected: No"));

        f.run(sender, "voice");

        verify(f.diagnostics).describe(sender.getUniqueId());
        verify(f.server, never()).getPlayerExact(anyString());
        assertEquals("Voice chat for Director", messages(sender).getFirst());
        assertTrue(messages(sender).contains("Connected: No"));
    }

    @Test void missingOrDisconnectedPlayersAreReportedWithoutQueryingVoiceState() {
        Fixture f = new Fixture();
        CommandSender sender = f.staff();
        Player disconnected = f.player("Offline");
        when(disconnected.isOnline()).thenReturn(false);
        when(f.server.getPlayerExact("Offline")).thenReturn(disconnected);

        f.run(sender, "voice", "Missing");
        f.run(sender, "voice", "Offline");

        assertEquals(List.of("Missing is not online.", "Offline is not online."), messages(sender));
        verifyNoInteractions(f.diagnostics);
    }

    @Test void consoleMustProvideAPlayerAndExtraArgumentsAreRejected() {
        Fixture f = new Fixture();
        CommandSender sender = f.staff();

        f.run(sender, "voice");
        f.run(sender, "voice", "Alice", "extra");

        assertEquals(List.of("Usage: /contender voice <player>", "Usage: /contender voice <player>"), messages(sender));
        verifyNoInteractions(f.server, f.diagnostics);
    }

    @Test void permissionDenialCannotExposeAnotherPlayersDiagnostics() {
        Fixture f = new Fixture();
        Player sender = f.player("Contestant");

        f.run(sender, "voice", "Alice");

        verifyNoInteractions(f.server, f.diagnostics);
        verify(sender, never()).sendMessage(any(Component.class));
        verify(sender).sendActionBar(argThat((Component message) -> plain(message).contains("permission")));
    }

    @Test void generalUsageListsReloadAndVoice() {
        Fixture f = new Fixture();
        CommandSender sender = f.staff();

        f.run(sender);

        List<String> lines = messages(sender);
        assertTrue(lines.stream().anyMatch(line -> line.contains("/contender reload")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("/contender voice [player]")));
        verifyNoInteractions(f.server, f.diagnostics);
    }

    private static List<String> messages(CommandSender sender) {
        ArgumentCaptor<Component> captured = ArgumentCaptor.forClass(Component.class);
        verify(sender, atLeastOnce()).sendMessage(captured.capture());
        return captured.getAllValues().stream().map(VoiceStatusCommandTest::plain).toList();
    }

    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }

    private static final class Fixture {
        final Contender plugin = mock(Contender.class);
        final Server server = mock(Server.class);
        final VoiceDiagnostics diagnostics = mock(VoiceDiagnostics.class);
        final ContenderCommand command = new ContenderCommand(plugin);

        Fixture() {
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getVoiceDiagnostics()).thenReturn(diagnostics);
        }

        CommandSender staff() {
            CommandSender sender = mock(CommandSender.class);
            when(sender.hasPermission("contender.master")).thenReturn(true);
            return sender;
        }

        Player player(String name) {
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getName()).thenReturn(name);
            when(player.isOnline()).thenReturn(true);
            return player;
        }

        void run(CommandSender sender, String... args) {
            assertTrue(command.onCommand(sender, mock(Command.class), "contender", args));
        }
    }
}
