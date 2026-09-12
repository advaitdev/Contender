package me.advait.contender.combo;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Mannequin;
import org.bukkit.profile.PlayerTextures;
import java.util.concurrent.ThreadLocalRandom;

/** Vanilla client assets and player sounds for the practice mannequin. */
final class ComboAppearance {
    private ComboAppearance() { }

    static void apply(Mannequin bot) {
        // With neither a name nor UUID this profile stays static: no account lookup or skin download.
        bot.setProfile(ResolvableProfile.resolvableProfile()
                .skinPatch(patch -> patch.body(Key.key("minecraft", "entity/player/wide/steve"))
                        .model(PlayerTextures.SkinModel.CLASSIC))
                .build());
        bot.setSilent(true);
    }

    /** Called for an accepted sword hit while Paper is still dispatching the damage event. */
    static void hurt(Mannequin bot) {
        // Vanilla does not repeat its hurt sound when a stronger hit only adds damage during immunity.
        if (bot.getNoDamageTicks() > bot.getMaximumNoDamageTicks() / 2.0) return;
        var random = ThreadLocalRandom.current();
        float pitch = (random.nextFloat() - random.nextFloat()) * 0.2f + 1.0f;
        var location = bot.getLocation();
        location.getWorld().playSound(location, "minecraft:entity.player.hurt", SoundCategory.PLAYERS, 1.0f, pitch);
    }
}
