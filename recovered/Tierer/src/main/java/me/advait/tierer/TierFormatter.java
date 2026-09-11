/*
 * Decompiled with CFR 0.152.
 */
package me.advait.tierer;

import java.util.LinkedHashMap;
import java.util.Map;
import me.advait.tierer.PlayerData;
import me.advait.tierer.Ranking;

public class TierFormatter {
    private static final Map<String, String> EMOJIS = new LinkedHashMap<String, String>();
    private static final Map<String, String> TITLES = new LinkedHashMap<String, String>();

    public static String color(int tier) {
        return switch (tier) {
            case 1 -> "\u00a7e";
            case 2 -> "\u00a7b";
            case 3 -> "\u00a76";
            default -> "\u00a77";
        };
    }

    public static String emoji(String gamemode) {
        return EMOJIS.getOrDefault(gamemode.toLowerCase(), "");
    }

    public static String title(String gamemode) {
        return TITLES.getOrDefault(gamemode.toLowerCase(), Character.toUpperCase(gamemode.charAt(0)) + gamemode.substring(1));
    }

    public static String abbreviate(int tier, int pos) {
        return (pos == 0 ? "HT" : "LT") + tier;
    }

    public static String formatEntry(String gamemode, int tier, int pos, boolean retired) {
        String color = TierFormatter.color(tier);
        String em = TierFormatter.emoji(gamemode);
        String abbrev = TierFormatter.abbreviate(tier, pos);
        String retiredStr = "";
        String sep = em.isEmpty() ? "" : " ";
        return color + em + sep + retiredStr + abbrev;
    }

    public static int score(int tier, int pos) {
        return tier * 2 + pos;
    }

    public static String getBestTier(PlayerData data) {
        if (data == null || data.getRankings() == null || data.getRankings().isEmpty()) {
            return "";
        }
        String bestGamemode = null;
        int bestTier = Integer.MAX_VALUE;
        int bestPos = Integer.MAX_VALUE;
        boolean bestRetired = false;
        for (Map.Entry<String, Ranking> entry : data.getRankings().entrySet()) {
            int effPos;
            String gamemode = entry.getKey();
            Ranking ranking = entry.getValue();
            int effTier = ranking.getEffectiveTier();
            if (TierFormatter.score(effTier, effPos = ranking.getEffectivePos()) >= TierFormatter.score(bestTier, bestPos)) continue;
            bestGamemode = gamemode;
            bestTier = effTier;
            bestPos = effPos;
            bestRetired = ranking.isRetired();
        }
        if (bestGamemode == null) {
            return "";
        }
        return TierFormatter.formatEntry(bestGamemode, bestTier, bestPos, bestRetired);
    }

    static {
        EMOJIS.put("axe", "\ud83e\ude93");
        EMOJIS.put("pot", "\u2697");
        EMOJIS.put("vanilla", "\u2726");
        EMOJIS.put("uhc", "\u2764");
        EMOJIS.put("sword", "\ud83d\udde1");
        EMOJIS.put("smp", "\u26e8");
        EMOJIS.put("nop", "\u2620");
        TITLES.put("axe", "Axe");
        TITLES.put("pot", "Pot");
        TITLES.put("vanilla", "Vanilla");
        TITLES.put("uhc", "UHC");
        TITLES.put("sword", "Sword");
        TITLES.put("smp", "SMP");
        TITLES.put("nop", "Netherite OP");
    }
}
