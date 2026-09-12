package me.advait.contender.minigame;

import me.advait.contender.Contender;
import me.advait.contender.lobby.LobbyManager;
import me.advait.contender.role.RoleManager;
import me.advait.contender.util.YamlStorage;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerReturnStoreTest {
    @TempDir Path folder;
    @Test void noClearKitCannotMutateTheCapturedInventoryAndDeathDefersRecovery() {
        var plugin = mock(Contender.class); when(plugin.getDataFolder()).thenReturn(folder.toFile());
        var lobby = mock(LobbyManager.class); when(plugin.getLobbyManager()).thenReturn(lobby);
        when(plugin.getRoleManager()).thenReturn(mock(RoleManager.class));
        var player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL); when(player.getMaxHealth()).thenReturn(20d);
        when(player.getHealth()).thenReturn(20d);
        var inventory = mock(PlayerInventory.class); when(player.getInventory()).thenReturn(inventory);
        var live = mock(ItemStack.class); var saved = mock(ItemStack.class); when(live.clone()).thenReturn(saved);
        ItemStack[] contents = new ItemStack[41]; contents[0] = live; when(inventory.getContents()).thenReturn(contents);
        var position = new Location(mock(World.class), 0, 80, 0); when(lobby.getLobbyLocation()).thenReturn(position);
        when(player.teleport(position)).thenReturn(true);
        try (var storage = mockStatic(YamlStorage.class)) {
            var returns = new PlayerReturnStore(plugin, "return-test.yml"); returns.capture(player);
            live.setAmount(0); contents[0] = null;
            when(player.isDead()).thenReturn(true);
            assertFalse(returns.restore(player)); verify(inventory, never()).setContents(any());
            when(player.isDead()).thenReturn(false); assertTrue(returns.restore(player));
            verify(inventory).setContents(argThat(items -> items.length == 41 && items[0] == saved));
            assertFalse(returns.pending(player.getUniqueId()));
        }
    }
}
