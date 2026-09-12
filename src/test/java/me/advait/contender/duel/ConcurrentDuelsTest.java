package me.advait.contender.duel;

import me.advait.contender.Contender;
import me.advait.contender.arena.ArenaInstance;
import me.advait.contender.arena.ArenaLease;
import me.advait.contender.arena.ArenaManager;
import me.advait.contender.kit.Kit;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.vote.VoteManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConcurrentDuelsTest {
    private DuelSetup setup(ArenaMap map, UUID first, UUID second) {
        DuelSetup setup = new DuelSetup(UUID.randomUUID());
        setup.setSelectedMap(map);
        setup.setSelectedKit(mock(Kit.class));
        setup.getTeam1().addPlayer(first);
        setup.getTeam2().addPlayer(second);
        return setup;
    }
    @Test void sameMapRunsTwoDuelsWithoutEnrollingBystandersOrSharingPlayers() {
        Contender plugin = mock(Contender.class);
        ArenaManager arenas = mock(ArenaManager.class);
        when(plugin.getArenaManager()).thenReturn(arenas);
        when(plugin.getVoteManager()).thenReturn(mock(VoteManager.class));
        var roles = mock(me.advait.contender.role.RoleManager.class);
        when(roles.isContestant(any())).thenReturn(true);
        when(plugin.getRoleManager()).thenReturn(roles);
        DuelManager manager = new DuelManager(plugin);
        List<UUID> ids = new ArrayList<>();
        Map<UUID, Player> online = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(id);
            when(player.getName()).thenReturn("Player" + i);
            online.put(id, player);
        }
        ArenaMap map = new ArenaMap("forest");
        ArenaLease firstArena = new ArenaLease(mock(ArenaInstance.class), UUID.randomUUID());
        ArenaLease secondArena = new ArenaLease(mock(ArenaInstance.class), UUID.randomUUID());
        when(arenas.acquire("forest")).thenReturn(firstArena, secondArena);
        try (var bukkit = mockStatic(Bukkit.class); var construction = mockConstruction(Duel.class, (duel, context) -> {
            DuelSetup setup = (DuelSetup) context.arguments().get(2);
            when(duel.getAllDuelPlayers()).thenReturn(setup.getAllPlayers());
            when(duel.getAllParticipants()).thenReturn(setup.getAllPlayers());
            when(duel.getArena()).thenReturn((ArenaLease) context.arguments().get(3));
            when(duel.isInDuel(any())).thenAnswer(call -> setup.getAllPlayers().contains(call.getArgument(0)));
            when(duel.getResult()).thenReturn(new DuelResult(DuelResult.Reason.FINISHED, 2, 1, 1));
        })) {
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(call -> online.get(call.getArgument(0)));
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(online.values());
            Consumer<DuelResult> completion = mock(Consumer.class);
            Duel first = manager.startDuel(setup(map, ids.get(0), ids.get(1)), completion);
            Duel second = manager.startDuel(setup(map, ids.get(2), ids.get(3)));
            assertEquals(2, manager.getActiveDuels().size());
            assertSame(first, manager.getDuel(ids.get(0)));
            assertSame(second, manager.getDuel(ids.get(2)));
            assertNull(manager.getDuel(ids.get(4)));
            verify(online.get(ids.get(4)), never()).getInventory();
            verify(first, never()).addSpectator(any());
            verify(second, never()).addSpectator(any());
            assertThrows(IllegalStateException.class, () -> manager.startDuel(setup(map, ids.get(0), ids.get(4))));
            verify(arenas, times(2)).acquire("forest");
            Player visitor = online.get(ids.get(4));
            when(visitor.getInventory()).thenReturn(mock(org.bukkit.inventory.PlayerInventory.class));
            AbstractDuelState state = mock(AbstractDuelState.class);
            when(state.canAddSpectator()).thenReturn(true);
            when(first.getState()).thenReturn(state);
            manager.spectate(visitor, first);
            assertSame(first, manager.getDuel(visitor));
            doAnswer(call -> {
                assertNull(manager.getDuel(visitor), "Release ownership before the lobby teleport can trigger confinement");
                return null;
            }).when(first).removeSpectator(visitor);
            manager.leaveSpectating(visitor);
            assertNull(manager.getDuel(visitor));
            assertEquals(2, manager.getActiveDuels().size());
            manager.endDuel(first);
            manager.endDuel(first);
            assertNull(manager.getDuel(ids.get(0)));
            assertSame(second, manager.getDuel(ids.get(2)));
            verify(arenas).release(firstArena);
            verify(completion, times(1)).accept(any());
            verify(arenas, never()).release(secondArena);

            manager.createSetup(ids.get(4));
            doThrow(new IllegalStateException("Test cleanup failure")).when(second).forceCancel();
            assertThrows(IllegalStateException.class, manager::forceCancelAll);
            assertTrue(manager.getActiveDuels().isEmpty());
            for (UUID id : ids) assertNull(manager.getDuel(id));
            assertNull(manager.getSetup(ids.get(4)));
            verify(completion, times(1)).accept(any());
            assertDoesNotThrow(() -> manager.startDuel(setup(map, ids.get(0), ids.get(1))),
                    "Emergency cancellation must not permanently disable new duels");
        }
    }
}
