package me.advait.contender.command;

import me.advait.contender.Contender;
import me.advait.contender.duel.Duel;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.tournament.TournamentManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class CancelDuelCommandTest {
    @Test void targetsAnotherPlayersDuelAndExplainsTheTournamentPause() {
        Contender plugin = mock(Contender.class); Server server = mock(Server.class);
        DuelManager duels = mock(DuelManager.class); TournamentManager tournaments = mock(TournamentManager.class);
        when(plugin.getServer()).thenReturn(server); when(plugin.getDuelManager()).thenReturn(duels);
        when(plugin.getTournamentManager()).thenReturn(tournaments);
        CommandSender sender = mock(CommandSender.class); when(sender.hasPermission("contender.master")).thenReturn(true);
        Player target = mock(Player.class); when(server.getPlayerExact("Alice")).thenReturn(target);
        Duel duel = mock(Duel.class); when(duels.getDuel(target)).thenReturn(duel); when(tournaments.cancelDuel(duel)).thenReturn(true);
        new CancelDuelCommand(plugin).onCommand(sender, mock(Command.class), "cancelduel", new String[]{"Alice"});
        verify(tournaments).cancelDuel(duel); verify(duel, never()).endDuel();
        verify(sender).sendMessage(argThat((Component message) -> PlainTextComponentSerializer.plainText().serialize(message).contains("Tournament paused")));
    }
    @Test void permissionDenialAndMissingTargetsCannotCancelADuel() {
        Contender plugin = mock(Contender.class); Server server = mock(Server.class); when(plugin.getServer()).thenReturn(server);
        CommandSender sender = mock(CommandSender.class); CancelDuelCommand command = new CancelDuelCommand(plugin);
        command.onCommand(sender, mock(Command.class), "cancelduel", new String[]{"Alice"});
        verify(plugin, never()).getDuelManager();
        when(sender.hasPermission("contender.master")).thenReturn(true);
        command.onCommand(sender, mock(Command.class), "cancelduel", new String[]{});
        command.onCommand(sender, mock(Command.class), "cancelduel", new String[]{"Missing"});
        verify(plugin, never()).getTournamentManager();
    }
}
