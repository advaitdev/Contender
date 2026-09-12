package me.advait.contender.duel;

import com.destroystokyo.paper.profile.PlayerProfile;
import me.advait.contender.kit.Kit;
import me.advait.contender.lobby.LobbyManager;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.testutil.StateTestServer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DuelSpectatorVisibilityTest {
    private Player player(World world, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true); when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(player.getInventory()).thenReturn(mock(PlayerInventory.class));
        PlayerProfile profile = mock(PlayerProfile.class);
        when(profile.getProperties()).thenReturn(Set.of());
        when(player.getPlayerProfile()).thenReturn(profile);
        return player;
    }

    @Test
    void updatesOnEliminationAndRoundResetAndClearsOnForcedEnd() {
        try (var server = new StateTestServer(); var bukkit = mockStatic(Bukkit.class);
             var registries = mockStatic(io.papermc.paper.registry.RegistryAccess.class);
             var bridges = mockStatic(io.papermc.paper.InternalAPIBridge.class)) {
            var bridge = mock(io.papermc.paper.InternalAPIBridge.class);
            bridges.when(io.papermc.paper.InternalAPIBridge::get).thenReturn(bridge);
            var access = mock(io.papermc.paper.registry.RegistryAccess.class, RETURNS_MOCKS);
            registries.when(io.papermc.paper.registry.RegistryAccess::registryAccess).thenReturn(access);
            var rules = new java.util.HashMap<net.kyori.adventure.key.Key, org.bukkit.GameRule<?>>();
            doAnswer(call -> rules.computeIfAbsent(call.getArgument(0), key -> mock(org.bukkit.GameRule.class)))
                    .when(org.bukkit.Registry.GAME_RULE).getOrThrow(any(net.kyori.adventure.key.Key.class));
            World world = mock(World.class);
            Player first = player(world, "First");
            Player teammate = player(world, "Teammate");
            Player opponent = player(world, "Opponent");
            Player spectator = player(world, "Spectator");
            List<Player> players = List.of(first, teammate, opponent, spectator);
            Map<UUID, Player> online = players.stream().collect(Collectors.toMap(Player::getUniqueId, p -> p));
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(call -> online.get(call.getArgument(0)));
            bukkit.when(() -> Bukkit.getOfflinePlayer(any(UUID.class))).thenAnswer(call -> online.get(call.getArgument(0)));
            bukkit.when(Bukkit::getPluginManager).thenReturn(server.plugins);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            when(server.plugin.getLobbyManager()).thenReturn(mock(LobbyManager.class));
            doReturn(players).when(server.server).getOnlinePlayers();
            when(server.plugin.createSpectatorAvatar(any())).thenAnswer(call -> mock(me.advait.contender.spectator.AllayAvatar.class));
            when(server.plugin.getDuelManager()).thenReturn(mock(DuelManager.class));
            var tags = mock(me.advait.contender.nametag.NameTagManager.class);
            when(server.plugin.getNameTagManager()).thenReturn(tags);
            when(tags.displayName(any(Player.class))).thenReturn(net.kyori.adventure.text.Component.text("Spectator"));

            DuelSetup setup = new DuelSetup(first.getUniqueId());
            ArenaMap map = mock(ArenaMap.class);
            when(map.getWorldName()).thenReturn("test");
            when(map.getTeam1Spawn()).thenReturn(new Location(world, 0, 64, 0));
            when(map.getTeam2Spawn()).thenReturn(new Location(world, 0, 64, 0));
            setup.setSelectedMap(map);
            setup.setSelectedKit(mock(Kit.class));
            setup.getTeam1().addPlayer(first.getUniqueId());
            setup.getTeam1().addPlayer(teammate.getUniqueId());
            setup.getTeam2().addPlayer(opponent.getUniqueId());
            Duel duel = new Duel(server.plugin, mock(DuelManager.class), setup);
            duel.addSpectator(spectator.getUniqueId());
            duel.start();
            verify(world).setGameRule(org.bukkit.GameRules.LOCATOR_BAR, false);
            verify(world).setGameRule(org.bukkit.GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
            var visibilityTimer = server.scheduled.getFirst();
            verify(first).hidePlayer(server.plugin, spectator);
            verify(spectator, never()).hidePlayer(any(), any());

            duel.setState(new ActiveState(duel));
            duel.handleDeath(first, opponent);
            verify(first, never()).showPlayer(server.plugin, spectator);
            verify(teammate).hidePlayer(server.plugin, first);
            verify(spectator).hidePlayer(server.plugin, first);

            duel.prepareRound();
            verify(teammate).showPlayer(server.plugin, first);
            verify(first).hidePlayer(server.plugin, spectator);

            duel.forceEnd();
            assertTrue(duel.isFinished());
            verify(visibilityTimer.task()).cancel();
            verify(first).showPlayer(server.plugin, spectator);
            verify(teammate).showPlayer(server.plugin, spectator);
            verify(opponent).showPlayer(server.plugin, spectator);
            visibilityTimer.run();
            verify(first).hidePlayer(server.plugin, spectator);
            for (Player player : players) {
                verify(player, never()).setVelocity(any());
                verify(player, never()).showBossBar(any(net.kyori.adventure.bossbar.BossBar.class));
                verify(player, never()).sendActionBar(argThat((net.kyori.adventure.text.Component message) -> {
                    String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message);
                    return !text.isEmpty() && !text.startsWith("Starts in ");
                }));
            }
        }
    }
}
