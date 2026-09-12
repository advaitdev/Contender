package me.advait.contender.gui.duel;

import me.advait.contender.kit.Kit;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KitEditorGUITest {
    @Test void reopeningANewKitAfterTogglingRulesKeepsItsUnsavedEquipment() {
        Kit draft = new Kit("axe");
        ItemStack weapon = mock(ItemStack.class);
        ItemStack helmet = mock(ItemStack.class);
        ItemStack shield = mock(ItemStack.class);
        when(weapon.clone()).thenReturn(weapon);
        when(helmet.clone()).thenReturn(helmet);
        when(shield.clone()).thenReturn(shield);
        draft.setContents(new ItemStack[]{weapon});
        draft.setArmor(new ItemStack[]{null, null, null, helmet});
        draft.setOffhand(shield);
        draft.setNaturalRegen(false);
        Player player = mock(Player.class);
        Inventory inventory = mock(Inventory.class);

        try (var bukkit = mockStatic(Bukkit.class);
             var items = mockConstruction(ItemStack.class, (item, context) ->
                     when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(Component.class)))
                    .thenReturn(inventory);

            KitEditorGUI.open(player, draft, true);

            verify(inventory).setItem(0, weapon);
            verify(inventory).setItem(KitEditorGUI.HELMET_SLOT, helmet);
            verify(inventory).setItem(KitEditorGUI.OFFHAND_SLOT, shield);
            verify(player).openInventory(inventory);
        }
    }
}
