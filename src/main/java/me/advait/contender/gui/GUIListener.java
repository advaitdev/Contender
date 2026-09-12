package me.advait.contender.gui;

import me.advait.contender.Contender;
import me.advait.contender.gui.duel.KitEditorGUI;
import me.advait.contender.kit.Kit;
import me.advait.contender.kit.KitManager;
import me.advait.contender.util.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Inventory handling is limited to the kit editor's item slots. */
public class GUIListener implements Listener {
    private final KitManager kitManager;
    public GUIListener(Contender plugin) { kitManager = plugin.getKitManager(); }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof GUIHolder holder)) return;
        if (!player.hasPermission("contender.master")) { event.setCancelled(true); player.closeInventory(); return; }
        handleKitEditor(event, player, holder);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof GUIHolder)) return;
        if (!event.getWhoClicked().hasPermission("contender.master")) { event.setCancelled(true); return; }
        for (int slot : event.getRawSlots()) {
            if (slot >= 36 && slot < 54) { event.setCancelled(true); return; }
        }
    }

    private void handleKitEditor(InventoryClickEvent event, Player player, GUIHolder holder) {
        int rawSlot = event.getRawSlot();
        Kit kit = holder.getData("kit");
        boolean isNew = holder.getData("isNew");
        if (kit == null) return;

        // Main inventory item slots — allow vanilla behavior
        if (rawSlot >= 0 && rawSlot < 36) {
            return;
        }

        // Armor and offhand slots — custom handling
        if (rawSlot >= 36 && rawSlot <= 40) {
            event.setCancelled(true);
            if (event.isRightClick()) {
                // Reset to placeholder
                event.setCurrentItem(KitEditorGUI.getSlotPlaceholder(rawSlot));
            } else if (event.isLeftClick()) {
                ItemStack cursor = event.getCursor();
                if (cursor != null && cursor.getType() != Material.AIR) {
                    // Place the cursor item into the slot
                    event.setCurrentItem(cursor.clone());
                    event.getView().setCursor(new ItemStack(Material.AIR));
                }
                // Left-click with empty cursor — do nothing (don't let placeholder be picked up)
            }
            return;
        }

        // Player's own inventory
        if (rawSlot >= 54) {
            return;
        }

        event.setCancelled(true);

        switch (rawSlot) {
            case KitEditorGUI.PVP_HURT_SLOT, KitEditorGUI.PVE_HURT_SLOT -> {
                saveKitFromInventory(event.getInventory(), kit);
                if (rawSlot == KitEditorGUI.PVP_HURT_SLOT) kit.setPvpHurt(!kit.isPvpHurt());
                else kit.setPveHurt(!kit.isPveHurt());
                MessageUtil.playClick(player);
                KitEditorGUI.open(player, kit, isNew);
            }
            case KitEditorGUI.BLOCK_PLACE_SLOT -> {
                saveKitFromInventory(event.getInventory(), kit);
                kit.setAllowBlockPlace(!kit.isAllowBlockPlace());
                MessageUtil.playClick(player);
                KitEditorGUI.open(player, kit, isNew);
            }
            case KitEditorGUI.BLOCK_BREAK_SLOT -> {
                saveKitFromInventory(event.getInventory(), kit);
                kit.setAllowBlockBreak(!kit.isAllowBlockBreak());
                MessageUtil.playClick(player);
                KitEditorGUI.open(player, kit, isNew);
            }
            case KitEditorGUI.NATURAL_REGEN_SLOT -> {
                saveKitFromInventory(event.getInventory(), kit);
                kit.setNaturalRegen(!kit.isNaturalRegen());
                MessageUtil.playClick(player);
                KitEditorGUI.open(player, kit, isNew);
            }
            case KitEditorGUI.SPECTATOR_INVISIBLE_SLOT -> {
                saveKitFromInventory(event.getInventory(), kit);
                kit.setSpectatorInvisible(!kit.isSpectatorInvisible());
                MessageUtil.playClick(player);
                KitEditorGUI.open(player, kit, isNew);
            }
            case KitEditorGUI.NO_CLEAR_SLOT -> {
                saveKitFromInventory(event.getInventory(), kit);
                kit.setNoClear(!kit.isNoClear());
                MessageUtil.playClick(player);
                KitEditorGUI.open(player, kit, isNew);
            }
            case KitEditorGUI.SAVE_SLOT -> {
                saveKitFromInventory(event.getInventory(), kit);
                kitManager.saveKit(kit);
                player.closeInventory();
                player.sendMessage(MessageUtil.parse(
                        "<color:" + MessageUtil.PRIMARY + ">Kit '" + kit.getDisplayName() + "' saved!</color>"));
                MessageUtil.playSuccess(player);
            }
            case KitEditorGUI.CANCEL_SLOT -> {
                player.closeInventory();
                player.sendMessage(MessageUtil.parse(
                        "<color:" + MessageUtil.ERROR + ">Kit editing cancelled</color>"));
            }
            case KitEditorGUI.DELETE_SLOT -> {
                if (event.isShiftClick() && !isNew) {
                    String name = kit.getDisplayName();
                    kitManager.deleteKit(kit.getId());
                    player.closeInventory();
                    player.sendMessage(MessageUtil.parse(
                            "<color:" + MessageUtil.ERROR + ">Kit '" + name + "' deleted!</color>"));
                }
            }
            case KitEditorGUI.ICON_SLOT -> {
                ItemStack cursor = event.getCursor();
                if (cursor != null && cursor.getType() != Material.AIR) {
                    saveKitFromInventory(event.getInventory(), kit);
                    kit.setIcon(cursor.getType());
                    MessageUtil.playClick(player);
                    KitEditorGUI.open(player, kit, isNew);
                }
            }
        }
    }

    private void saveKitFromInventory(Inventory inv, Kit kit) {
        ItemStack[] contents = new ItemStack[36];
        for (int i = 0; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                contents[i] = item.clone();
            }
        }
        kit.setContents(contents);

        ItemStack[] armor = new ItemStack[4];
        ItemStack boots = inv.getItem(KitEditorGUI.BOOTS_SLOT);
        ItemStack leggings = inv.getItem(KitEditorGUI.LEGGINGS_SLOT);
        ItemStack chestplate = inv.getItem(KitEditorGUI.CHESTPLATE_SLOT);
        ItemStack helmet = inv.getItem(KitEditorGUI.HELMET_SLOT);

        if (boots != null && boots.getType() != Material.AIR && boots.getType() != Material.ARMOR_STAND)
            armor[0] = boots.clone();
        if (leggings != null && leggings.getType() != Material.AIR && leggings.getType() != Material.ARMOR_STAND)
            armor[1] = leggings.clone();
        if (chestplate != null && chestplate.getType() != Material.AIR && chestplate.getType() != Material.ARMOR_STAND)
            armor[2] = chestplate.clone();
        if (helmet != null && helmet.getType() != Material.AIR && helmet.getType() != Material.ARMOR_STAND)
            armor[3] = helmet.clone();
        kit.setArmor(armor);

        ItemStack offhand = inv.getItem(KitEditorGUI.OFFHAND_SLOT);
        if (offhand != null && offhand.getType() != Material.AIR && offhand.getType() != Material.RED_STAINED_GLASS_PANE) {
            kit.setOffhand(offhand.clone());
        } else {
            kit.setOffhand(null);
        }
    }

}
