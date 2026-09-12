package me.advait.contender.command;

import me.advait.contender.Contender;
import me.advait.contender.hacker.HackerManager;
import org.bukkit.Server;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;

class HackerCommandsTest {
    @Test void commandRegistrationKeepsAdminChecksAndGatesHacksBySelectionEvenForOperators() {
        Contender plugin = mock(Contender.class); HackerManager hackers = mock(HackerManager.class);
        when(plugin.getHackerManager()).thenReturn(hackers);
        when(plugin.getResource("plugin.yml")).thenAnswer(call -> getClass().getResourceAsStream("/plugin.yml"));
        var commands = new PaperCommands(plugin); CommandExecutor executor = mock(CommandExecutor.class);
        Player player = mock(Player.class); UUID id = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id);
        var hacks = commands.command("hacks"); hacks.setExecutor(executor);
        when(player.hasPermission(anyString())).thenReturn(true);
        hacks.execute(player, "hacks", new String[0]); verifyNoInteractions(executor);
        when(hackers.isHacker(id)).thenReturn(true); hacks.execute(player, "hacks", new String[0]);
        verify(executor).onCommand(eq(player), eq(hacks), eq("hacks"), any());
        clearInvocations(executor); when(hackers.isHacker(id)).thenReturn(false);
        hacks.execute(player, "hacks", new String[0]); verifyNoInteractions(executor);
        var pick = commands.command("pickhacker"); pick.setExecutor(executor);
        when(player.hasPermission("contender.master")).thenReturn(false);
        pick.execute(player, "pickhacker", new String[]{"Alice"}); verifyNoInteractions(executor);
        when(player.hasPermission("contender.master")).thenReturn(true);
        pick.execute(player, "pickhacker", new String[]{"Alice"}); verify(executor).onCommand(eq(player), eq(pick), eq("pickhacker"), any());
    }
    @Test void pickingValidatesAllNamesBeforeChangingTheSelectionAndSupportsKnownOfflinePlayers() {
        Contender plugin = mock(Contender.class); Server server = mock(Server.class); HackerManager hackers = mock(HackerManager.class);
        when(plugin.getServer()).thenReturn(server); when(plugin.getHackerManager()).thenReturn(hackers);
        CommandSender sender = mock(CommandSender.class); var command = new PickHackerCommand(plugin);
        command.onCommand(sender, mock(Command.class), "pickhacker", new String[]{"Alice"}); verifyNoInteractions(hackers);
        when(sender.hasPermission("contender.master")).thenReturn(true);
        OfflinePlayer alice = mock(OfflinePlayer.class); when(alice.getName()).thenReturn("Alice"); when(server.getOfflinePlayerIfCached("Alice")).thenReturn(alice);
        command.onCommand(sender, mock(Command.class), "pickhacker", new String[]{"Alice", "Unknown"}); verifyNoInteractions(hackers);
        command.onCommand(sender, mock(Command.class), "pickhacker", new String[]{"Alice"}); verify(hackers).pick(List.of(alice));
        command.onCommand(sender, mock(Command.class), "pickhacker", new String[]{"--clear"}); verify(hackers).pick(List.of());
    }
}
