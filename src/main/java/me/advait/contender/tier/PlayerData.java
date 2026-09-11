package me.advait.contender.tier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record PlayerData(String uuid, String name, String region, Map<String, Ranking> rankings) {
    public PlayerData {
        rankings = rankings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(rankings));
    }
}
