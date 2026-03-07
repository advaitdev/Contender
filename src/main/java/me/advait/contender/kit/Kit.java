package me.advait.contender.kit;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class Kit {

    private final String id;
    private String displayName;
    private ItemStack[] contents;
    private ItemStack[] armor;
    private ItemStack offhand;
    private Material icon;
    private boolean allowBlockPlace;
    private boolean allowBlockBreak;
    private boolean naturalRegen;

    public Kit(String id) {
        this.id = id;
        this.displayName = id;
        this.contents = new ItemStack[36];
        this.armor = new ItemStack[4];
        this.offhand = null;
        this.icon = Material.DIAMOND_SWORD;
        this.allowBlockPlace = false;
        this.allowBlockBreak = false;
        this.naturalRegen = true;
    }

    public void apply(Player player) {
        player.getInventory().clear();
        player.getInventory().setContents(cloneArray(contents));
        player.getInventory().setArmorContents(cloneArray(armor));
        player.getInventory().setItemInOffHand(offhand == null ? null : offhand.clone());

        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(5.0f);
        player.setFireTicks(0);
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));

        player.updateInventory();
    }

    private ItemStack[] cloneArray(ItemStack[] original) {
        if (original == null) return new ItemStack[0];
        ItemStack[] clone = new ItemStack[original.length];
        for (int i = 0; i < original.length; i++) {
            if (original[i] != null) {
                clone[i] = original[i].clone();
            }
        }
        return clone;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public ItemStack[] getContents() {
        return contents;
    }

    public void setContents(ItemStack[] contents) {
        this.contents = contents;
    }

    public ItemStack[] getArmor() {
        return armor;
    }

    public void setArmor(ItemStack[] armor) {
        this.armor = armor;
    }

    public ItemStack getOffhand() {
        return offhand;
    }

    public void setOffhand(ItemStack offhand) {
        this.offhand = offhand;
    }

    public Material getIcon() {
        return icon;
    }

    public void setIcon(Material icon) {
        this.icon = icon;
    }

    public boolean isAllowBlockPlace() {
        return allowBlockPlace;
    }

    public void setAllowBlockPlace(boolean allowBlockPlace) {
        this.allowBlockPlace = allowBlockPlace;
    }

    public boolean isAllowBlockBreak() {
        return allowBlockBreak;
    }

    public void setAllowBlockBreak(boolean allowBlockBreak) {
        this.allowBlockBreak = allowBlockBreak;
    }

    public boolean isNaturalRegen() {
        return naturalRegen;
    }

    public void setNaturalRegen(boolean naturalRegen) {
        this.naturalRegen = naturalRegen;
    }
}
