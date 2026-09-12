package me.advait.contender.combo;

import me.advait.contender.Contender;
import me.advait.contender.kit.*;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComboKitTest {
    @Test void rejectsEmptyAndNoClearKitsBeforePlayersInventoriesAreChanged() throws Exception {
        Contender plugin = mock(Contender.class); KitManager kits = mock(KitManager.class); when(plugin.getKitManager()).thenReturn(kits);
        ComboManager manager = mock(ComboManager.class, CALLS_REAL_METHODS);
        var owner = ComboManager.class.getDeclaredField("contender"); owner.setAccessible(true); owner.set(manager, plugin);
        Kit kit = new Kit("sword"); when(kits.getKit("sword")).thenReturn(kit);
        assertThrows(IllegalArgumentException.class, () -> manager.requireSwordKit("missing"));
        assertThrows(IllegalArgumentException.class, () -> manager.requireSwordKit("sword"));
        ItemStack sword = mock(ItemStack.class); when(sword.getType()).thenReturn(Material.DIAMOND_SWORD); kit.setContents(new ItemStack[]{sword});
        assertSame(kit, manager.requireSwordKit("sword"));
        kit.setNoClear(true); assertThrows(IllegalArgumentException.class, () -> manager.requireSwordKit("sword"));
    }
}
