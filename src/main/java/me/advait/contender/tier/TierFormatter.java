package me.advait.contender.tier;

import net.kyori.adventure.text.Component;
import me.advait.contender.dialog.DialogIcon;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.*;

/** Tierer's tier colors with vanilla item sprites for each mode. */
public final class TierFormatter {
    private static final Map<String, DialogIcon> ICONS = Map.of("axe", DialogIcon.AXE, "pot", DialogIcon.POTION,
            "vanilla", DialogIcon.CRYSTAL, "uhc", DialogIcon.APPLE, "sword", DialogIcon.DUEL,
            "smp", DialogIcon.PLAYERS, "nop", DialogIcon.SPLASH_POTION, "nethop", DialogIcon.SPLASH_POTION);
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
        DialogIcon icon = ICONS.get(rank.mode());
        if (icon != null) result = result.append(icon.sprite()).append(Component.space());
        if (rank.retired()) result = result.append(Component.text("(R) ", NamedTextColor.GRAY));
        return result.append(Component.text(rank.tier().label(), color));
    }
    public static Component prefix(PlayerData data) {
        return highest(data).map(t -> format(t).append(Component.text(" | ", NamedTextColor.GRAY))).orElse(Component.empty());
    }
}
