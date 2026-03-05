package me.advait.contender.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

public class GUIHolder implements InventoryHolder {

    private final GUIType type;
    private final Map<String, Object> data;

    public GUIHolder(GUIType type) {
        this.type = type;
        this.data = new HashMap<>();
    }

    public GUIType getType() {
        return type;
    }

    public void setData(String key, Object value) {
        data.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getData(String key) {
        return (T) data.get(key);
    }

    public boolean hasData(String key) {
        return data.containsKey(key);
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
