package me.advait.contender.race;

import me.advait.contender.Contender;
import me.advait.contender.minigame.PlayerReturnStore;
import org.bukkit.entity.Player;

/** Keep the original recovery file so inventories from older versions are restored. */
final class RaceReturns extends PlayerReturnStore {
    RaceReturns(Contender plugin) { super(plugin, "race-returns.yml"); }

    @Override public void capture(Player player) {
        if (pending(player.getUniqueId())) {
            // Keep the existing snapshot and leave its owner's inventory view alone.
            super.capture(player);
            return;
        }
        if (player.isDead()) throw new IllegalStateException("Wait for " + player.getName() + " to respawn before starting the race.");
        // Vanilla returns cursor and crafting items to the inventory when its view closes.
        player.closeInventory();
        super.capture(player);
    }

    @Override public boolean restore(Player player) {
        if (!pending(player.getUniqueId()) || player.isDead()) return super.restore(player);
        var carried = player.getItemOnCursor();
        player.setItemOnCursor(null);
        player.closeInventory();
        boolean restored = super.restore(player);
        // A blocked teleport leaves the race loadout in place until recovery can retry.
        if (!restored) player.setItemOnCursor(carried);
        return restored;
    }
}
