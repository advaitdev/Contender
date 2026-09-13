package me.advait.contender.lobby;

import me.advait.contender.Contender;
import me.advait.contender.arena.ArenaManager;
import me.advait.contender.duel.DuelManager;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LobbyManagerTest {
    @Test void savedLobbyLoadsAutomaticallyBeforeChoosingAFallbackSpawn() {
        Contender plugin = mock(Contender.class);
        var config = new YamlConfiguration(); config.set("lobby.world", "studio");
        config.set("lobby.x", 12.5); config.set("lobby.y", 87); config.set("lobby.z", -4.5);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getArenaManager()).thenReturn(mock(ArenaManager.class));
        var worlds = mock(me.advait.contender.world.ManagedWorlds.class);
        when(plugin.getManagedWorlds()).thenReturn(worlds);
        World studio = mock(World.class);
        when(worlds.loadExisting("studio")).thenReturn(studio);
        try (var bukkit = mockStatic(Bukkit.class)) {
            var lobby = new LobbyManager(plugin);
            assertEquals(new Location(studio, 12.5, 87, -4.5), lobby.getLobbyLocation());
            verify(worlds).loadExisting("studio");
            bukkit.verify(Bukkit::getWorlds, never());
        }
    }

    @Test void failedAutomaticLobbyLoadKeepsTheFallbackAvailable() {
        Contender plugin = mock(Contender.class);
        var config = new YamlConfiguration(); config.set("lobby.world", "missing_studio");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getArenaManager()).thenReturn(mock(ArenaManager.class));
        when(plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class));
        var worlds = mock(me.advait.contender.world.ManagedWorlds.class);
        when(plugin.getManagedWorlds()).thenReturn(worlds);
        when(worlds.loadExisting("missing_studio")).thenThrow(new IllegalArgumentException("Saved world files are missing."));
        World fallback = mock(World.class);
        var spawn = new Location(fallback, 0, 70, 0); when(fallback.getSpawnLocation()).thenReturn(spawn);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(java.util.List.of(fallback));
            assertEquals(spawn, new LobbyManager(plugin).getLobbyLocation());
            verify(plugin.getLogger()).warning(contains("Saved world files are missing"));
        }
    }

    @Test void returningToTheLobbyAppliesTheSavedSpectatorRole() {
        Contender plugin = mock(Contender.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        var roles = mock(me.advait.contender.role.RoleManager.class);
        when(plugin.getRoleManager()).thenReturn(roles);
        Player player = mock(Player.class, RETURNS_DEEP_STUBS);
        when(player.teleport(any(Location.class))).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getMaxHealth()).thenReturn(20.0);
        when(roles.getRole(player.getUniqueId())).thenReturn(me.advait.contender.role.PlayerRole.SPECTATOR);
        LobbyManager lobby = spy(new LobbyManager(plugin));
        Location destination = new Location(mock(World.class), 0, 80, 0);
        doReturn(destination).when(lobby).getLobbyLocation();
        when(player.getWorld()).thenReturn(destination.getWorld());
        when(player.getLocation()).thenReturn(destination.clone());
        lobby.sendToLobby(player);
        verify(player).setGameMode(GameMode.SPECTATOR);
        when(roles.getRole(player.getUniqueId())).thenReturn(me.advait.contender.role.PlayerRole.CONTESTANT);
        lobby.sendToLobby(player);
        verify(player).setGameMode(GameMode.SURVIVAL);
    }
    @Test void savesCoordinatesAndFacingAndRejectsAnArenaPoolLocation() {
        Contender plugin = mock(Contender.class); YamlConfiguration config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        ArenaManager arenas = mock(ArenaManager.class); when(plugin.getArenaManager()).thenReturn(arenas);
        LobbyManager lobby = new LobbyManager(plugin);
        World world = mock(World.class); when(world.getName()).thenReturn("lobby");
        when(world.getKey()).thenReturn(NamespacedKey.minecraft("lobby"));
        Location location = new Location(world, 4.5, 81, -30.5, 125, -10);
        lobby.setLobbyLocation(location);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("lobby")).thenReturn(world);
            assertEquals(location, lobby.getLobbyLocation());
        }
        when(arenas.isArenaWorld(world)).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> lobby.setLobbyLocation(location));
        verify(plugin, times(1)).saveConfig();
        assertEquals("minecraft:lobby", config.getString("lobby.world-key"));
    }
    @Test void managedEndCannotBeUsedAsTheLobby() {
        Contender plugin = mock(Contender.class); when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getArenaManager()).thenReturn(mock(ArenaManager.class));
        var minigames = mock(me.advait.contender.minigame.MinigameManager.class); when(plugin.getMinigameManager()).thenReturn(minigames);
        when(minigames.inArena(any())).thenReturn(true);
        var location = new Location(mock(World.class), 0, 80, 0);
        assertThrows(IllegalArgumentException.class, () -> new LobbyManager(plugin).setLobbyLocation(location));
        verify(plugin, never()).saveConfig();
    }
    @Test void resolvesAWorldByItsSavedKeyAndPreservesTheExactSpawn() {
        Contender plugin = mock(Contender.class);
        YamlConfiguration config = new YamlConfiguration(); when(plugin.getConfig()).thenReturn(config);
        config.set("lobby.world", "old_name"); config.set("lobby.world-key", "builder:lobby");
        config.set("lobby.x", 23.5); config.set("lobby.y", 95); config.set("lobby.z", -12.5);
        config.set("lobby.yaw", 175); config.set("lobby.pitch", 10);
        ArenaManager arenas = mock(ArenaManager.class); when(plugin.getArenaManager()).thenReturn(arenas);
        World world = mock(World.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(NamespacedKey.fromString("builder:lobby"))).thenReturn(world);
            LobbyManager lobby = new LobbyManager(plugin);
            assertEquals(new Location(world, 23.5, 95, -12.5, 175, 10), lobby.getLobbyLocation());
            assertTrue(lobby.isLobbyWorld(world));
        }
    }
    @Test void missingWorldWarnsOnceProtectsTheFallbackAndRecoversWhenLoaded() {
        Contender plugin = mock(Contender.class);
        YamlConfiguration config = new YamlConfiguration(); when(plugin.getConfig()).thenReturn(config);
        config.set("lobby.world", "lobby"); config.set("lobby.y", 90);
        var logger = mock(java.util.logging.Logger.class); when(plugin.getLogger()).thenReturn(logger);
        ArenaManager arenas = mock(ArenaManager.class); when(plugin.getArenaManager()).thenReturn(arenas);
        World pool = mock(World.class), fallback = mock(World.class), saved = mock(World.class);
        when(arenas.isArenaWorld(pool)).thenReturn(true);
        Location spawn = new Location(fallback, 10, 80, 10); when(fallback.getSpawnLocation()).thenReturn(spawn);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(java.util.List.of(pool, fallback));
            LobbyManager lobby = new LobbyManager(plugin);
            assertEquals(spawn, lobby.getLobbyLocation()); assertEquals(spawn, lobby.getLobbyLocation());
            assertTrue(lobby.isLobbyWorld(fallback)); assertFalse(lobby.isLobbyWorld(pool));
            verify(logger, times(1)).warning(contains("not loaded"));
            bukkit.when(() -> Bukkit.getWorld("lobby")).thenReturn(saved);
            assertEquals(saved, lobby.getLobbyLocation().getWorld()); assertEquals(90, lobby.getLobbyLocation().getY());
            assertTrue(lobby.isLobbyWorld(saved)); assertFalse(lobby.isLobbyWorld(fallback));
        }
    }
    @Test void cancelledLobbyTeleportDoesNotClearInventoryOrChangeGameMode() {
        Contender plugin = mock(Contender.class);
        var logger = mock(java.util.logging.Logger.class); when(plugin.getLogger()).thenReturn(logger);
        Player player = mock(Player.class, RETURNS_DEEP_STUBS);
        LobbyManager lobby = spy(new LobbyManager(plugin));
        doReturn(new Location(mock(World.class), 0, 80, 0)).when(lobby).getLobbyLocation();
        assertFalse(lobby.sendToLobbyChecked(player));
        verify(player.getInventory(), never()).clear(); verify(player, never()).setGameMode(any());
        verify(player, never()).getActivePotionEffects();
        verify(player, never()).setHealth(anyDouble());
        verify(logger).warning(contains("teleport was cancelled"));
    }
    @Test void missingLobbyDoesNotTeleportOrChangePlayerState() {
        Contender plugin = mock(Contender.class);
        Player player = mock(Player.class);
        LobbyManager lobby = spy(new LobbyManager(plugin));
        doReturn(null).when(lobby).getLobbyLocation();

        assertFalse(lobby.sendToLobbyChecked(player));

        verifyNoInteractions(player);
        verify(plugin, never()).getRoleManager();
    }
    @Test void redirectedLobbyTeleportDoesNotResetThePlayerEvenWhenTeleportReturnsTrue() {
        Contender plugin = mock(Contender.class);
        when(plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class));
        Player player = mock(Player.class, RETURNS_DEEP_STUBS);
        LobbyManager lobby = spy(new LobbyManager(plugin));
        Location destination = new Location(mock(World.class), 12.5, 80, -9.5);
        doReturn(destination).when(lobby).getLobbyLocation();
        when(player.getWorld()).thenReturn(destination.getWorld());
        when(player.getLocation()).thenReturn(destination.clone().add(10, 0, 0));
        when(player.teleport(any(Location.class))).thenAnswer(invocation -> {
            // A teleport listener may change the requested location as well as the player's destination.
            Location requested = invocation.getArgument(0);
            requested.add(10, 0, 0);
            return true;
        });

        assertFalse(lobby.sendToLobbyChecked(player));

        assertEquals(12.5, destination.getX());
        verify(player.getInventory(), never()).clear();
        verify(player, never()).setGameMode(any());
        verify(player, never()).getActivePotionEffects();
        verify(player, never()).setHealth(anyDouble());
        verify(player, never()).setFoodLevel(anyInt());
        verify(plugin, never()).getRoleManager();
        verify(plugin.getLogger()).warning(contains("teleport was redirected"));
    }
    @Test void lobbyTeleportToTheWrongWorldDoesNotResetThePlayer() {
        Contender plugin = mock(Contender.class);
        when(plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class));
        Player player = mock(Player.class, RETURNS_DEEP_STUBS);
        LobbyManager lobby = spy(new LobbyManager(plugin));
        Location destination = new Location(mock(World.class), 0, 80, 0);
        World otherWorld = mock(World.class);
        doReturn(destination).when(lobby).getLobbyLocation();
        when(player.teleport(any(Location.class))).thenReturn(true);
        when(player.getWorld()).thenReturn(otherWorld);
        when(player.getLocation()).thenReturn(new Location(otherWorld, 0, 80, 0));

        assertFalse(lobby.sendToLobbyChecked(player));

        verify(player.getInventory(), never()).clear();
        verify(player, never()).setGameMode(any());
        verify(player, never()).getActivePotionEffects();
        verify(plugin, never()).getRoleManager();
    }
    @Test void confirmedLobbyArrivalResetsPlayerStateAndReturnsSuccess() {
        Contender plugin = mock(Contender.class);
        var roles = mock(me.advait.contender.role.RoleManager.class);
        when(plugin.getRoleManager()).thenReturn(roles);
        Player player = mock(Player.class, RETURNS_DEEP_STUBS);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getMaxHealth()).thenReturn(20.0);
        when(roles.getRole(player.getUniqueId())).thenReturn(me.advait.contender.role.PlayerRole.CONTESTANT);
        LobbyManager lobby = spy(new LobbyManager(plugin));
        Location destination = new Location(mock(World.class), 12.5, 80, -9.5);
        doReturn(destination).when(lobby).getLobbyLocation();
        var position = new java.util.concurrent.atomic.AtomicReference<>(destination.clone().add(50, 0, 0));
        when(player.getLocation()).thenAnswer(invocation -> position.get().clone());
        when(player.getWorld()).thenAnswer(invocation -> position.get().getWorld());
        when(player.teleport(any(Location.class))).thenAnswer(invocation -> {
            position.set(((Location) invocation.getArgument(0)).clone());
            return true;
        });

        assertTrue(lobby.sendToLobbyChecked(player));

        assertEquals(destination, player.getLocation());
        verify(player.getInventory()).clear();
        verify(player).getActivePotionEffects();
        verify(player).setFireTicks(0);
        verify(player).setHealth(20);
        verify(player).setFoodLevel(20);
        verify(player).setSaturation(5.0f);
        verify(player).setLevel(0);
        verify(player).setExp(0);
        verify(player).setGameMode(GameMode.SURVIVAL);
    }
    @Test void oldBuildSwitchStillControlsPlacementUntilTheNewSettingIsSaved() {
        Contender plugin = mock(Contender.class);
        YamlConfiguration config = new YamlConfiguration(); when(plugin.getConfig()).thenReturn(config);
        config.addDefault("lobby.allow-block-place", false); config.set("lobby.allow-block-break", true);
        LobbyManager lobby = new LobbyManager(plugin);
        assertTrue(lobby.isAllowBlockPlace());
        config.set("lobby.allow-block-place", false);
        assertFalse(lobby.isAllowBlockPlace()); assertTrue(lobby.isAllowBlockBreak());
    }
    @Test void deadContestantsStillInAMatchCannotUseTheLobbyShortcut() {
        Contender plugin = mock(Contender.class); DuelManager duels = mock(DuelManager.class);
        when(plugin.getDuelManager()).thenReturn(duels);
        LobbyManager lobby = new LobbyManager(plugin);
        Player player = mock(Player.class); UUID id = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id);
        when(duels.isPlaying(id)).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> lobby.returnToLobby(player));
        verify(duels, never()).leaveSpectating(any()); verify(player, never()).teleport(any(Location.class));
    }
}
