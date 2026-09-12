package me.advait.contender.race;

import me.advait.contender.kit.Kit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RaceKitTest {
    @Test void checkpointBedRelocatesTheNinthHotbarItemWithoutChangingTheSavedKit() {
        var item = item(Material.GOLDEN_CARROT);
        var mace = item(Material.MACE);
        ItemStack[] source = new ItemStack[36]; source[0] = mace; source[8] = item;

        var prepared = RaceKit.prepareContents(source);

        assertSame(mace, prepared[0]);
        assertNull(prepared[8]);
        assertSame(item, prepared[9]);
        assertSame(item, source[8]);
        assertNull(source[9]);
    }

    @Test void relocationUsesAnotherHotbarSlotWhenTheMainInventoryIsFull() {
        ItemStack[] source = new ItemStack[36]; Arrays.fill(source, item(Material.WIND_CHARGE));
        source[3] = null;

        var prepared = RaceKit.prepareContents(source);

        assertSame(source[8], prepared[3]);
        assertNull(prepared[8]);
        assertNull(source[3]);
    }

    @Test void aFullKitIsRejectedBeforeChangingThePlayer() {
        Kit kit = new Kit("full"); ItemStack[] contents = new ItemStack[36];
        Arrays.fill(contents, item(Material.MACE)); kit.setContents(contents);
        Player player = mock(Player.class);

        assertThrows(IllegalArgumentException.class, () -> RaceKit.give(player, kit));
        verifyNoInteractions(player);
        assertSame(contents, kit.getContents());
    }

    @Test void customItemsArmorAndOffhandAreAppliedWhileHungerStaysFull() {
        Kit kit = new Kit("custom");
        var mace = item(Material.MACE); var carrots = item(Material.GOLDEN_CARROT);
        var helmet = item(Material.DIAMOND_HELMET); var offhand = item(Material.TOTEM_OF_UNDYING);
        ItemStack[] contents = new ItemStack[36]; contents[0] = mace; contents[8] = carrots;
        kit.setContents(contents); kit.setArmor(new ItemStack[]{null, null, null, helmet}); kit.setOffhand(offhand);
        Player player = mock(Player.class); PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory); when(player.getActivePotionEffects()).thenReturn(List.of());

        try (var beds = mockConstruction(ItemStack.class)) {
            RaceKit.give(player, kit);

            assertEquals(1, beds.constructed().size());
            ItemStack bed = beds.constructed().getFirst();
            verify(inventory).setStorageContents(argThat(items -> items.length == 36 && items[0] == mace && items[8] == bed && items[9] == carrots));
            verify(inventory).setArmorContents(argThat(items -> items[3] == helmet));
            verify(inventory).setItemInOffHand(offhand);
            verify(player).setSaturation(20);
            verify(player, atLeastOnce()).setFoodLevel(20);
            verify(player, atLeastOnce()).setExhaustion(0);
            assertFalse(kit.isPvpHurt()); assertFalse(kit.isPveHurt());
            assertSame(carrots, kit.getContents()[8]); assertNull(kit.getContents()[9]);
        }
    }

    @Test void noClearKitsCannotBypassTheSavedRaceLoadout() {
        Kit kit = new Kit("own_items"); kit.setNoClear(true);
        assertThrows(IllegalArgumentException.class, () -> RaceKit.validate(kit));
    }

    @Test void checkpointHitsAcceptAllSpearMaterialsAndMaces() {
        assertTrue(RaceKit.isWeapon(item(Material.MACE)));
        var spears = Arrays.stream(Material.values()).filter(material -> material.name().endsWith("_SPEAR")).toList();
        assertTrue(spears.size() > 1);
        spears.forEach(material -> assertTrue(RaceKit.isWeapon(item(material)), material.name()));
        assertFalse(RaceKit.isWeapon(item(Material.NETHERITE_SWORD)));
        assertFalse(RaceKit.isWeapon(item(Material.WIND_CHARGE)));
        assertFalse(RaceKit.isWeapon(null));
    }

    private ItemStack item(Material material) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material); when(item.getAmount()).thenReturn(1); when(item.clone()).thenReturn(item);
        return item;
    }
}
