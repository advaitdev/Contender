package me.advait.contender.vote;

import me.advait.contender.duel.DuelManager;
import me.advait.contender.testutil.StateTestServer;
import me.advait.contender.tournament.TournamentManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VoteCancellationTest {
    @Test void cancelledVoteCannotAnnounceResultsOrAffectItsReplacement() {
        try (var env = new StateTestServer(); var bukkit = mockStatic(Bukkit.class)) {
            when(env.plugin.getConfig()).thenReturn(new YamlConfiguration());
            when(env.plugin.getTournamentManager()).thenReturn(mock(TournamentManager.class));
            var player = mock(Player.class);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            bukkit.when(Bukkit::getScheduler).thenReturn(env.scheduler);
            var manager = new VoteManager(env.plugin, mock(DuelManager.class));
            assertTrue(manager.startVote(1));
            var old = manager.getActiveSession();
            var timer = env.scheduled.getLast();
            clearInvocations(player);

            manager.forceCancel();
            assertTrue(old.isEnded()); assertFalse(manager.isVoteActive());
            verify(timer.task()).cancel();
            verify(player).sendActionBar(Component.empty());
            verify(player, never()).sendMessage(any(Component.class));
            assertTrue(manager.startVote(30));
            var replacement = manager.getActiveSession();
            clearInvocations(player);
            timer.run(); old.end();
            assertSame(replacement, manager.getActiveSession());
            assertEquals(30, replacement.getRemainingSeconds());
            verifyNoInteractions(player);
            manager.forceCancel(); manager.forceCancel();
            assertFalse(manager.isVoteActive());
        }
    }
}
