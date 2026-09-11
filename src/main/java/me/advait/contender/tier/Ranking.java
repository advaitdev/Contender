package me.advait.contender.tier;

import com.google.gson.annotations.SerializedName;
import java.util.Optional;

/** Recovered from Tierer, with aliases for the API's snake_case peak fields. */
public record Ranking(Integer tier, Integer pos,
                      @SerializedName(value = "peak_tier", alternate = "peakTier") Integer peakTier,
                      @SerializedName(value = "peak_pos", alternate = "peakPos") Integer peakPos,
                      boolean retired, long attained) {
    public record Tier(int tier, int pos) implements Comparable<Tier> {
        public String label() { return (pos == 0 ? "HT" : "LT") + tier; }
        @Override public int compareTo(Tier other) {
            int compared = Integer.compare(tier, other.tier);
            return compared != 0 ? compared : Integer.compare(pos, other.pos);
        }
    }
    public Optional<Tier> effective() {
        Integer position = peakPos == null ? pos : peakPos;
        if (retired && valid(peakTier, position)) return Optional.of(new Tier(peakTier, position));
        return valid(tier, pos) ? Optional.of(new Tier(tier, pos)) : Optional.empty();
    }
    private static boolean valid(Integer tier, Integer pos) {
        return tier != null && tier >= 1 && tier <= 5 && pos != null && (pos == 0 || pos == 1);
    }
}
