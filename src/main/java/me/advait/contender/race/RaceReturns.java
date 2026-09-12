package me.advait.contender.race;

import me.advait.contender.Contender;
import me.advait.contender.minigame.PlayerReturnStore;

/** Keep the original recovery file so inventories from older versions are restored. */
final class RaceReturns extends PlayerReturnStore {
    RaceReturns(Contender plugin) { super(plugin, "race-returns.yml"); }
}
