package me.advait.contender.util;

import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;

public final class StringUtil {

    private StringUtil() { }

    /** Dynamic fallback when only a UUID is known. */
    public static Component getPlayerHead(UUID uuid) {
        return Component.object(ObjectContents.playerHead(uuid))
                .color(NamedTextColor.WHITE).shadowColor(ShadowColor.none());
    }

    public static Component getPlayerHead(Player player) {
        var profile = player.getPlayerProfile();
        return resolvedHead(player.getUniqueId(), player.getName(), profile.getProperties());
    }

    /**
     * Adapted from UHCR's Icons.resolvedHead. Supplying both identity fields and
     * the existing skin properties avoids a separate client-side profile lookup.
     * Append surrounding text as siblings so it does not inherit the head's shadow.
     */
    public static Component resolvedHead(UUID id, String name, Collection<ProfileProperty> properties) {
        var head = ObjectContents.playerHead().id(id)
                .name(name != null && PlayerHeadObjectContents.isValidName(name) ? name : "Player")
                .hat(true);
        for (ProfileProperty property : properties) {
            if (property.getName().equals("textures")) {
                head.profileProperty(PlayerHeadObjectContents.property(
                        property.getName(), property.getValue(), property.getSignature()));
            }
        }
        return Component.object(head.build()).color(NamedTextColor.WHITE).shadowColor(ShadowColor.none());
    }
}
