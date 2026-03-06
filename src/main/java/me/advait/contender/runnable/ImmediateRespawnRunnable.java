package me.advait.contender.runnable;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;

public class ImmediateRespawnRunnable implements Runnable {

    @Override
    public void run() {
        for (World world : Bukkit.getWorlds()) {
            world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        }
    }

}
