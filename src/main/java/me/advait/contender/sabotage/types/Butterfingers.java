package me.advait.contender.sabotage.types;

import me.advait.contender.core.Msg;
import me.advait.contender.core.Sounds;
import me.advait.contender.dialog.DialogIcon;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.sabotage.Sabotage;
import me.advait.contender.sabotage.SabotageContext;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Hotbars get shuffled every so often. */
public final class Butterfingers implements Sabotage {
    @Override public String id() { return "butterfingers"; }
    @Override public String name() { return "Butterfingers"; }
    @Override public String description() { return "Hotbars shuffle every few seconds."; }
    @Override public DialogIcon icon() { return DialogIcon.SLIME; }

    @Override public void tick(SabotageContext context, long second) {
        int interval = Math.clamp(context.settings().getInt("interval-seconds", 20), 5, 300);
        if (second == 0 || second % interval != 0) return;
        for (Player player : context.affectedPlayers()) {
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.CREATIVE) continue;
            var inventory = player.getInventory();
            List<ItemStack> hotbar = new ArrayList<>();
            for (int slot = 0; slot < 9; slot++) hotbar.add(inventory.getItem(slot));
            Collections.shuffle(hotbar);
            for (int slot = 0; slot < 9; slot++) inventory.setItem(slot, hotbar.get(slot));
            player.sendActionBar(Msg.text("Butterfingers!", DialogPalette.WARNING));
            Sounds.POP.play(player);
        }
    }
}
