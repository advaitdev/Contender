package me.advait.contender.tier;

import com.google.gson.Gson;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TierFormatterTest {
    private PlayerData parse(String ranks) {
        return new Gson().fromJson("{\"rankings\":" + ranks + "}", PlayerData.class);
    }
    @Test void retiredPeakOutranksWeakerActiveTierUsingTheActualApiFieldNames() {
        PlayerData data = parse("""
                {"sword":{"tier":2,"pos":0,"retired":false},
                 "axe":{"tier":3,"pos":1,"peak_tier":1,"peak_pos":1,"retired":true}}
                """);
        var best = TierFormatter.highest(data).orElseThrow();
        assertEquals("axe", best.mode());
        assertEquals("LT1", best.tier().label());
        assertTrue(best.retired());
    }
    @Test void strongerActiveTierWinsAndActiveHistoricalPeaksDoNotReplaceCurrentRatings() {
        PlayerData data = parse("""
                {"sword":{"tier":2,"pos":0,"peak_tier":1,"peak_pos":0,"retired":false},
                 "axe":{"tier":2,"pos":1,"retired":true}}
                """);
        var best = TierFormatter.highest(data).orElseThrow();
        assertEquals("HT2", best.tier().label());
        assertFalse(best.retired());
    }
    @Test void retiredRanksFallBackWhenPeakIsAbsentAndAlsoAcceptRecoveredCamelCaseFields() {
        var withoutPeak = TierFormatter.highest(parse("{\"smp\":{\"tier\":2,\"pos\":1,\"retired\":true}}")).orElseThrow();
        assertEquals("LT2", withoutPeak.tier().label());
        var camelCase = TierFormatter.highest(parse("{\"smp\":{\"tier\":3,\"pos\":1,\"peakTier\":1,\"peakPos\":0,\"retired\":true}}")).orElseThrow();
        assertEquals("HT1", camelCase.tier().label());
    }
    @Test void onlyRetirementMarkerIsLightGrayAndTierRetainsItsOriginalColor() {
        var rank = new TierFormatter.RankedTier("sword", new Ranking.Tier(2, 0), true);
        assertEquals(Component.empty().append(Component.text("🗡 ", NamedTextColor.AQUA))
                .append(Component.text("(R) ", NamedTextColor.GRAY)).append(Component.text("HT2", NamedTextColor.AQUA)), TierFormatter.format(rank));
        assertEquals(Component.empty().append(Component.text("🗡 ", NamedTextColor.AQUA))
                .append(Component.text("HT2", NamedTextColor.AQUA)), TierFormatter.format(new TierFormatter.RankedTier("sword", rank.tier(), false)));
    }
    @Test void invalidOrMissingRankingsDoNotProduceTags() {
        assertEquals(Component.empty(), TierFormatter.prefix(null));
        assertEquals(Component.empty(), TierFormatter.prefix(parse("{}")));
        assertEquals(Component.empty(), TierFormatter.prefix(parse("{\"axe\":null,\"sword\":{\"tier\":0,\"pos\":0},\"smp\":{\"tier\":1},\"pot\":{\"tier\":1,\"pos\":7}}")));
    }
    @Test void equalStrengthPrefersAnActiveRatingWithoutChangingTierOrder() {
        var data = new PlayerData(null, null, null, Map.of("axe", new Ranking(1, 1, null, null, true, 0),
                "sword", new Ranking(1, 1, null, null, false, 0)));
        assertEquals("sword", TierFormatter.highest(data).orElseThrow().mode());
    }
}
