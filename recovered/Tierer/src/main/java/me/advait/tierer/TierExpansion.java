/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  me.clip.placeholderapi.expansion.PlaceholderExpansion
 *  org.bukkit.OfflinePlayer
 *  org.jetbrains.annotations.NotNull
 *  org.jetbrains.annotations.Nullable
 */
package me.advait.tierer;

import java.util.UUID;
import me.advait.tierer.MCTiersAPI;
import me.advait.tierer.PlayerData;
import me.advait.tierer.TierFormatter;
import me.advait.tierer.Tierer;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class TierExpansion
extends PlaceholderExpansion {
    private final Tierer plugin;
    private final MCTiersAPI api;

    public TierExpansion(Tierer plugin, MCTiersAPI api) {
        this.plugin = plugin;
        this.api = api;
    }

    @NotNull
    public String getIdentifier() {
        return "tierer";
    }

    @NotNull
    public String getAuthor() {
        return "advait";
    }

    @NotNull
    public String getVersion() {
        return this.plugin.getDescription().getVersion();
    }

    public boolean persist() {
        return true;
    }

    public boolean canRegister() {
        return true;
    }

    @Nullable
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (!params.equalsIgnoreCase("tier") || player == null) {
            return null;
        }
        UUID uuid = player.getUniqueId();
        PlayerData cached = this.api.getCached(uuid);
        this.api.ensureFresh(uuid, null);
        if (cached == null) {
            return "";
        }
        return TierFormatter.getBestTier(cached) + "&7 | &r";
    }
}
