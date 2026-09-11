package me.advait.contender.tier;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.*;

/** Tierer's mode icons and colors, rendered as Adventure components. */
public final class TierFormatter {
    private static final Map<String, String> ICONS = Map.of("axe", "🪓", "pot", "⚗", "vanilla", "✦",
            "uhc", "❤", "sword", "🗡", "smp", "⛨", "nop", "☠", "nethop", "☠");
    private TierFormatter() { }
    public record RankedTier(String mode, Ranking.Tier tier, boolean retired) { }
    public static NamedTextColor color(int tier) {
        return switch (tier) {
            case 1 -> NamedTextColor.YELLOW;
            case 2 -> NamedTextColor.AQUA;
            case 3 -> NamedTextColor.GOLD;
            default -> NamedTextColor.GRAY;
        };
    }
    public static List<RankedTier> rankings(PlayerData data) {
        List<RankedTier> tiers = new ArrayList<>();
        if (data != null) data.rankings().forEach((mode, ranking) -> {
            if (mode != null && ranking != null) ranking.effective().ifPresent(t -> tiers.add(new RankedTier(mode, t, ranking.retired())));
        });
        tiers.sort(Comparator.comparing(RankedTier::tier).thenComparing(RankedTier::retired).thenComparing(RankedTier::mode));
        return List.copyOf(tiers);
    }
    public static Optional<RankedTier> highest(PlayerData data) { return rankings(data).stream().findFirst(); }
    public static Component format(RankedTier rank) {
        NamedTextColor color = color(rank.tier().tier());
        Component result = Component.empty();
        String icon = ICONS.getOrDefault(rank.mode(), "");
        if (!icon.isEmpty()) result = result.append(Component.text(icon + " ", color));
        if (rank.retired()) result = result.append(Component.text("(R) ", NamedTextColor.GRAY));
        return result.append(Component.text(rank.tier().label(), color));
    }
    public static Component prefix(PlayerData data) {
        return highest(data).map(t -> format(t).append(Component.text(" | ", NamedTextColor.GRAY))).orElse(Component.empty());
    }
}
