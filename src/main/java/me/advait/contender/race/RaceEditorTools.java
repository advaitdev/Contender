package me.advait.contender.race;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import me.advait.contender.Contender;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.dialog.Dialogs;
import me.advait.contender.game.AbstractGameState;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** A map-bound tool removes exactly the entity clicked, without dealing combat damage. */
final class RaceEditorTools extends AbstractGameState {
    static final NamespacedKey MAP = new NamespacedKey("contender", "race_remove_tool");
    static final NamespacedKey OWNER = new NamespacedKey("contender", "race_tool_owner");
    private final RaceManager manager;

    RaceEditorTools(Contender plugin, RaceManager manager) {
        super(plugin);
        this.manager = manager;
    }

    void give(Player player, String mapId, String mapName) {
        var inventory = player.getInventory();
        int slot = -1;
        ItemStack[] contents = inventory.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            if (owns(contents[i], player)) { slot = i; break; }
        }
        if (slot < 0) slot = inventory.firstEmpty();
        if (slot < 0 || slot >= contents.length) throw new IllegalStateException("Make room in your inventory for the removal tool.");
        ItemStack tool = new ItemStack(Material.SHEARS);
        tool.editMeta(meta -> {
            meta.displayName(DialogPalette.text("Remove Checkpoint", DialogPalette.DANGER));
            meta.lore(List.of(
                    DialogPalette.text(mapName, DialogPalette.TEXT),
                    DialogPalette.text("Click a checkpoint or finish mob to remove it.", DialogPalette.MUTED),
                    DialogPalette.text("The remaining checkpoints renumber automatically.", DialogPalette.MUTED),
                    DialogPalette.text("Save the course when you finish editing.", DialogPalette.MUTED)));
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(MAP, PersistentDataType.STRING, mapId);
            meta.getPersistentDataContainer().set(OWNER, PersistentDataType.STRING, player.getUniqueId().toString());
        });
        inventory.setItem(slot, tool);
        if (slot < 9) inventory.setHeldItemSlot(slot);
        player.closeDialog();
        Dialogs.tell(player, "Removal tool added. Hold the shears and click the mob you want to remove.");
    }

    private static String map(ItemStack item) {
        if (item == null || item.getType() != Material.SHEARS || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(MAP, PersistentDataType.STRING);
    }

    private static boolean owns(ItemStack item, Player player) {
        return map(item) != null && player.getUniqueId().toString().equals(
                item.getItemMeta().getPersistentDataContainer().get(OWNER, PersistentDataType.STRING));
    }

    // Run before the pending Mark Finish interaction, which takes precedence over the held tool.
    @EventHandler(priority = EventPriority.LOWEST)
    public void interact(PlayerInteractEntityEvent event) {
        ItemStack held = event.getPlayer().getInventory().getItem(event.getHand());
        if (map(held) == null || manager.markingFinish(event.getPlayer())) return;
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) remove(event.getPlayer(), held, event.getRightClicked());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void attack(PrePlayerAttackEntityEvent event) {
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        if (map(held) == null) return;
        event.setCancelled(true);
        if (!manager.markingFinish(event.getPlayer())) remove(event.getPlayer(), held, event.getAttacked());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void blockUse(PlayerInteractEvent event) { if (map(event.getItem()) != null) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void blockBreak(BlockBreakEvent event) {
        if (map(event.getPlayer().getInventory().getItemInMainHand()) != null) event.setCancelled(true);
    }

    private void remove(Player player, ItemStack held, Entity target) {
        if (!owns(held, player)) { Dialogs.error(player, "Get your own removal tool from Race Courses."); return; }
        if (!target.isValid()) return; // A second interaction packet can refer to the mob just removed.
        try {
            String name = RaceManager.plainName(target);
            manager.removeCourseMob(player, map(held), target).whenComplete((ignored, failure) -> {
                if (!isEnabled() || !player.isOnline()) return;
                if (failure != null) Dialogs.error(player, RaceManager.rootMessage(failure));
                else Dialogs.tell(player, "Removed " + name + ". Checkpoint order updated.");
            });
        } catch (RuntimeException failure) { Dialogs.error(player, Dialogs.message(failure)); }
    }
}
