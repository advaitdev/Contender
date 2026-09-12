package me.advait.contender.command;

import me.advait.contender.Contender;
import me.advait.contender.minigame.MinigameManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class CancelAllCommandTest {
    @Test void permissionIsRequiredAndInvalidArgumentsDoNotCancelAnything() {
        var plugin = mock(Contender.class); var events = mock(MinigameManager.class);
        when(plugin.getMinigameManager()).thenReturn(events);
        var sender = mock(CommandSender.class); var command = new CancelAllCommand(plugin);
        command.onCommand(sender, mock(Command.class), "cancelall", new String[0]);
        when(sender.hasPermission("contender.master")).thenReturn(true);
        command.onCommand(sender, mock(Command.class), "cancelall", new String[]{"unknown"});
        verifyNoInteractions(events);
    }

    @Test void consoleCanRecoverEventsAndReceivesPartialFailureInsteadOfSuccess() {
        var plugin = mock(Contender.class); var events = mock(MinigameManager.class);
        when(plugin.getMinigameManager()).thenReturn(events);
        var server = mock(Server.class); when(plugin.getServer()).thenReturn(server);
        when(server.getOnlinePlayers()).thenReturn(List.of());
        var sender = mock(CommandSender.class); when(sender.hasPermission("contender.master")).thenReturn(true);
        when(events.forceCancelAll()).thenReturn(List.of("Combo"));
        new CancelAllCommand(plugin).onCommand(sender, mock(Command.class), "cancelall", new String[0]);
        verify(events).forceCancelAll();
        verify(sender).sendMessage(Component.text("Cancellation needs attention: Combo. Check the server log.", NamedTextColor.RED));
    }
}
