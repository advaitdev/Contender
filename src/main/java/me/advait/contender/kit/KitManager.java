package me.advait.contender.kit;

import me.advait.contender.Contender;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class KitManager {

    private final Contender plugin;
    private final File kitFile;
    private final Map<String, Kit> kits;

    public KitManager(Contender plugin) {
        this.plugin = plugin;
        this.kitFile = new File(plugin.getDataFolder(), "kits.yml");
        this.kits = new LinkedHashMap<>();
        loadKits();
    }

    public void loadKits() {
        kits.clear();
        if (!kitFile.exists()) {
            kitFile.getParentFile().mkdirs();
            try {
                kitFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to create kits.yml: " + e.getMessage());
            }
            return;
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(kitFile);
        ConfigurationSection kitsSection = config.getConfigurationSection("kits");
        if (kitsSection == null) return;

        for (String id : kitsSection.getKeys(false)) {
            ConfigurationSection section = kitsSection.getConfigurationSection(id);
            if (section == null) continue;

            Kit kit = new Kit(id);
            kit.setDisplayName(section.getString("display-name", id));

            Material icon = Material.matchMaterial(section.getString("icon", "DIAMOND_SWORD"));
            kit.setIcon(icon != null ? icon : Material.DIAMOND_SWORD);

            kit.setAllowBlockPlace(section.getBoolean("allow-block-place", false));
            kit.setAllowBlockBreak(section.getBoolean("allow-block-break", false));
            kit.setNaturalRegen(section.getBoolean("natural-regen", true));
            kit.setSpectatorInvisible(section.getBoolean("spectator-invisible", false));

            if (section.contains("contents")) {
                @SuppressWarnings("unchecked")
                List<ItemStack> contentsList = (List<ItemStack>) section.getList("contents");
                if (contentsList != null) {
                    ItemStack[] contents = new ItemStack[36];
                    for (int i = 0; i < Math.min(contentsList.size(), 36); i++) {
                        contents[i] = contentsList.get(i);
                    }
                    kit.setContents(contents);
                }
            }

            if (section.contains("armor")) {
                @SuppressWarnings("unchecked")
                List<ItemStack> armorList = (List<ItemStack>) section.getList("armor");
                if (armorList != null) {
                    ItemStack[] armor = new ItemStack[4];
                    for (int i = 0; i < Math.min(armorList.size(), 4); i++) {
                        armor[i] = armorList.get(i);
                    }
                    kit.setArmor(armor);
                }
            }

            if (section.contains("offhand")) {
                kit.setOffhand(section.getItemStack("offhand"));
            }

            kits.put(id, kit);
        }
    }

    public void saveKits() {
        FileConfiguration config = new YamlConfiguration();

        for (Kit kit : kits.values()) {
            String path = "kits." + kit.getId();
            config.set(path + ".display-name", kit.getDisplayName());
            config.set(path + ".icon", kit.getIcon().name());
            config.set(path + ".allow-block-place", kit.isAllowBlockPlace());
            config.set(path + ".allow-block-break", kit.isAllowBlockBreak());
            config.set(path + ".natural-regen", kit.isNaturalRegen());
            config.set(path + ".spectator-invisible", kit.isSpectatorInvisible());
            config.set(path + ".contents", Arrays.asList(kit.getContents()));
            config.set(path + ".armor", Arrays.asList(kit.getArmor()));
            if (kit.getOffhand() != null) {
                config.set(path + ".offhand", kit.getOffhand());
            }
        }

        try {
            config.save(kitFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save kits.yml: " + e.getMessage());
        }
    }

    public void saveKit(Kit kit) {
        kits.put(kit.getId(), kit);
        saveKits();
    }

    public void deleteKit(String id) {
        kits.remove(id);
        saveKits();
    }

    public Kit getKit(String id) {
        return kits.get(id);
    }

    public Collection<Kit> getKits() {
        return Collections.unmodifiableCollection(kits.values());
    }

    public boolean kitExists(String id) {
        return kits.containsKey(id);
    }
}
