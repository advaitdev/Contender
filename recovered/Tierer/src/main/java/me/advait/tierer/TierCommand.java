/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  org.bukkit.command.Command
 *  org.bukkit.command.CommandExecutor
 *  org.bukkit.command.CommandSender
 *  org.bukkit.plugin.Plugin
 *  org.jetbrains.annotations.NotNull
 */
package me.advait.tierer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.advait.tierer.MCTiersAPI;
import me.advait.tierer.PlayerData;
import me.advait.tierer.Ranking;
import me.advait.tierer.TierFormatter;
import me.advait.tierer.Tierer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public class TierCommand
implements CommandExecutor {
    private final Tierer plugin;
    private final MCTiersAPI api;

    public TierCommand(Tierer plugin, MCTiersAPI api) {
        this.plugin = plugin;
        this.api = api;
    }

    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("\u00a7cUsage: /tier <player>");
            return true;
        }
        String name = args[0];
        sender.sendMessage("\u00a77Fetching MCTiers data for \u00a7f" + name + "\u00a77...");
        this.plugin.getServer().getScheduler().runTaskAsynchronously((Plugin)this.plugin, () -> {
            PlayerData data;
            try {
                data = this.api.fetchByName(name);
            }
            catch (Exception e) {
                this.sendSync(sender, "\u00a7cError contacting MCTiers API: " + e.getMessage());
                return;
            }
            if (data == null) {
                this.sendSync(sender, "\u00a7cPlayer \u00a7f" + name + " \u00a7cnot found on MCTiers.");
                return;
            }
            List<String> lines = this.buildOutput(data);
            this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, () -> {
                for (String line : lines) {
                    sender.sendMessage(line);
                }
            });
        });
        return true;
    }

    private List<String> buildOutput(PlayerData data) {
        ArrayList<String> lines = new ArrayList<String>();
        String region = data.getRegion() != null ? " \u00a78[" + data.getRegion() + "\u00a78]" : "";
        lines.add("\u00a78=== \u00a7f" + data.getName() + region + " \u00a78\u2014 \u00a7fMCTiers \u00a78===");
        Map<String, Ranking> rankings = data.getRankings();
        if (rankings == null || rankings.isEmpty()) {
            lines.add("\u00a77  No rankings found.");
            return lines;
        }
        for (Map.Entry<String, Ranking> entry : rankings.entrySet()) {
            String gamemode = entry.getKey();
            Ranking r = entry.getValue();
            int dispTier = r.getEffectiveTier();
            int dispPos = r.getEffectivePos();
            boolean retired = r.isRetired();
            String tierStr = TierFormatter.formatEntry(gamemode, dispTier, dispPos, retired);
            String gmTitle = TierFormatter.title(gamemode);
            Object peakStr = "";
            if (!retired && r.getPeakTier() != null) {
                String peakColor = TierFormatter.color(r.getPeakTier());
                peakStr = " \u00a78(peak: " + peakColor + TierFormatter.abbreviate(r.getPeakTier(), r.getPeakPos() != null ? r.getPeakPos().intValue() : r.getPos()) + "\u00a78)";
            }
            lines.add("  " + tierStr + " \u00a78\u2014 \u00a77" + gmTitle + (String)peakStr);
        }
        return lines;
    }

    private void sendSync(CommandSender sender, String msg) {
        this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, () -> sender.sendMessage(msg));
    }
}
