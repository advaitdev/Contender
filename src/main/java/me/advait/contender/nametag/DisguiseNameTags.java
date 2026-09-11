package me.advait.contender.nametag;

import me.libraryaddict.disguise.DisguiseAPI;
import me.libraryaddict.disguise.disguisetypes.Disguise;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

/** Loaded only when LibsDisguises is installed. */
public final class DisguiseNameTags {
    private DisguiseNameTags() { }
    public static void update(Player player, Component name) {
        Disguise disguise = DisguiseAPI.getDisguise(player);
        if (disguise != null) apply(disguise, name);
    }
    public static void apply(Disguise disguise, Component component) {
        String name = LegacyComponentSerializer.legacySection().serialize(component);
        var watcher = disguise.getWatcher();
        if (!name.equals(watcher.getCustomName())) watcher.setCustomName(name);
        if (!watcher.isCustomNameVisible()) watcher.setCustomNameVisible(true);
    }
}
