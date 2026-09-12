package me.advait.contender.race;

import me.advait.contender.Contender;
import me.advait.contender.lobby.LobbyManager;
import me.advait.contender.role.RoleManager;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RaceReturnsTest {
    @TempDir Path directory;
    @Test void snapshotSurvivesRestartAndBlockedTeleportUntilRestorationSucceeds() {
        Contender plugin = mock(Contender.class); when(plugin.getDataFolder()).thenReturn(directory.toFile());
        var lobby = mock(LobbyManager.class); var roles = mock(RoleManager.class);
        when(plugin.getLobbyManager()).thenReturn(lobby); when(plugin.getRoleManager()).thenReturn(roles);
        var location = new Location(mock(World.class), 1, 70, 1); when(lobby.getLobbyLocation()).thenReturn(location);
        Player player = mock(Player.class); UUID id = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id);
        PlayerInventory inventory = mock(PlayerInventory.class); when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(new ItemStack[41]); when(inventory.getHeldItemSlot()).thenReturn(5);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE); when(player.getHealth()).thenReturn(17d); when(player.getMaxHealth()).thenReturn(20d);
        when(player.getFoodLevel()).thenReturn(14); when(player.getSaturation()).thenReturn(3f); when(player.getLevel()).thenReturn(8); when(player.getExp()).thenReturn(.3f);
        when(player.getAllowFlight()).thenReturn(true); when(player.isFlying()).thenReturn(true);
        when(player.getActivePotionEffects()).thenReturn(List.of());
        var original = new RaceReturns(plugin); original.capture(player);
        assertTrue(original.pending(id)); verify(inventory, never()).clear();
        var restarted = new RaceReturns(plugin); assertTrue(restarted.pending(id));
        when(player.teleport(location)).thenReturn(false); assertFalse(restarted.restore(player)); assertTrue(restarted.pending(id));
        verify(inventory, never()).setContents(any());
        when(player.teleport(location)).thenReturn(true); assertTrue(restarted.restore(player));
        verify(inventory).setContents(argThat(items -> items.length == 41)); verify(inventory).setHeldItemSlot(5);
        verify(player).setGameMode(GameMode.CREATIVE); verify(player).setHealth(17); verify(player).setFoodLevel(14);
        verify(player).setLevel(8); verify(player).setExp(.3f); verify(player).setFlying(true);
        assertFalse(new RaceReturns(plugin).pending(id));
    }
}
