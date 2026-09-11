/*
 * Decompiled with CFR 0.152.
 */
package me.advait.tierer;

import java.util.Map;
import me.advait.tierer.Ranking;

public class PlayerData {
    private String uuid;
    private String name;
    private String region;
    private Map<String, Ranking> rankings;

    public String getUuid() {
        return this.uuid;
    }

    public String getName() {
        return this.name;
    }

    public String getRegion() {
        return this.region;
    }

    public Map<String, Ranking> getRankings() {
        return this.rankings;
    }
}
