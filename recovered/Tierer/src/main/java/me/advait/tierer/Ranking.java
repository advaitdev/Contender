/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.google.gson.annotations.SerializedName
 */
package me.advait.tierer;

import com.google.gson.annotations.SerializedName;

public class Ranking {
    private int tier;
    private int pos;
    @SerializedName(value="peak_tier")
    private Integer peakTier;
    @SerializedName(value="peak_pos")
    private Integer peakPos;
    private boolean retired;
    private long attained;

    public int getTier() {
        return this.tier;
    }

    public int getPos() {
        return this.pos;
    }

    public Integer getPeakTier() {
        return this.peakTier;
    }

    public Integer getPeakPos() {
        return this.peakPos;
    }

    public boolean isRetired() {
        return this.retired;
    }

    public long getAttained() {
        return this.attained;
    }

    public int getEffectiveTier() {
        if (this.retired && this.peakTier != null) {
            return this.peakTier;
        }
        return this.tier;
    }

    public int getEffectivePos() {
        if (this.retired && this.peakTier != null) {
            return this.peakPos != null ? this.peakPos : this.pos;
        }
        return this.pos;
    }
}
