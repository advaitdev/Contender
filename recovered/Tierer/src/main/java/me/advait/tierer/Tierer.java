/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  org.bukkit.command.CommandExecutor
 *  org.bukkit.command.PluginCommand
 *  org.bukkit.plugin.Plugin
 *  org.bukkit.plugin.java.JavaPlugin
 */
package me.advait.tierer;

import me.advait.tierer.MCTiersAPI;
import me.advait.tierer.TierCommand;
import me.advait.tierer.TierExpansion;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class Tierer
extends JavaPlugin {
    private MCTiersAPI api;

    public void onEnable() {
        this.api = new MCTiersAPI((Plugin)this);
        PluginCommand tierCmd = this.getCommand("tier");
        if (tierCmd != null) {
            tierCmd.setExecutor((CommandExecutor)new TierCommand(this, this.api));
        }
        if (this.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new TierExpansion(this, this.api).register();
            this.getLogger().info("PlaceholderAPI found \u2014 %tierer_tier% registered.");
        } else {
            this.getLogger().warning("PlaceholderAPI not found \u2014 %tierer_tier% placeholder will not work.");
        }
    }

    public void onDisable() {
    }

    public MCTiersAPI getApi() {
        return this.api;
    }
}
