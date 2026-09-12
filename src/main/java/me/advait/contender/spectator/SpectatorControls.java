package me.advait.contender.spectator;

import me.advait.contender.Contender;
import me.advait.contender.dialog.DuelDialogs;
import me.advait.contender.game.AbstractGameState;
import me.advait.contender.role.PlayerRole;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public final class SpectatorControls extends AbstractGameState {
    private final Contender contender;
    private final NamespacedKey compassKey;
    public SpectatorControls(Contender plugin) { super(plugin); contender = plugin; compassKey = new NamespacedKey(plugin, "spectate_compass"); }
    @Override protected void onEnable() { runRepeating(this::refresh, 1, 20); }
    @Override protected void onDisable() {
        for (Player player : plugin.getServer().getOnlinePlayers()) removeCompass(player);
    }
    public boolean watching(Player player) {
        var duel = contender.getDuelManager().getDuel(player);
        if (duel != null) return duel.isSpectator(player.getUniqueId());
        return contender.getRoleManager().getRole(player.getUniqueId()) != PlayerRole.CONTESTANT;
    }
    private void refresh() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (watching(player)) giveCompass(player); else removeCompass(player);
        }
    }
    private boolean compass(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(compassKey, PersistentDataType.BYTE);
    }
    public void giveCompass(Player player) {
        for (ItemStack item : player.getInventory().getContents()) if (compass(item)) return;
        int slot = player.getInventory().getItem(0) == null ? 0 : player.getInventory().firstEmpty();
        if (slot < 0) return;
        ItemStack item = new ItemStack(Material.COMPASS);
        item.editMeta(meta -> {
            meta.displayName(Component.text("Spectate matches", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Right-click to watch a match or return to the lobby.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(compassKey, PersistentDataType.BYTE, (byte) 1);
        });
        player.getInventory().setItem(slot, item);
    }
    private void removeCompass(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) if (compass(player.getInventory().getItem(slot))) player.getInventory().setItem(slot, null);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void interact(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick() || !compass(event.getItem())) return;
        event.setCancelled(true);
        if (watching(event.getPlayer())) new DuelDialogs(contender).spectate(event.getPlayer());
    }
    @EventHandler(ignoreCancelled = true) public void projectile(org.bukkit.event.entity.ProjectileHitEvent event) {
        if (event.getHitEntity() instanceof Player player) {
            var duel = contender.getDuelManager().getDuel(player);
            if (duel != null && duel.isSpectator(player.getUniqueId())) event.setCancelled(true);
        }
    }
    @EventHandler public void drop(PlayerDropItemEvent event) { if (compass(event.getItemDrop().getItemStack())) event.setCancelled(true); }
    @EventHandler public void swap(PlayerSwapHandItemsEvent event) {
        if (compass(event.getMainHandItem()) || compass(event.getOffHandItem())) event.setCancelled(true);
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (compass(event.getCurrentItem()) || compass(event.getCursor()) || event.getHotbarButton() >= 0
                && compass(event.getWhoClicked().getInventory().getItem(event.getHotbarButton()))) event.setCancelled(true);
    }
    @EventHandler public void drag(InventoryDragEvent event) { if (compass(event.getOldCursor())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) {
        contender.getDuelManager().disconnectSpectator(event.getPlayer());
        removeCompass(event.getPlayer());
    }
}
