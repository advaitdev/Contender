package me.advait.contender.hacker;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record HackSettings(Map<HackSetting, Double> values) {
    public HackSettings {
        var checked = new EnumMap<HackSetting, Double>(HackSetting.class);
        for (var setting : HackSetting.values()) checked.put(setting, setting.validate(values.getOrDefault(setting, setting.normal)));
        values = Map.copyOf(checked);
    }
    public static HackSettings defaults() { return new HackSettings(Map.of()); }
    public double get(HackSetting setting) { return values.get(setting); }
    public HackSettings with(Map<HackSetting, Double> changes) {
        var updated = new EnumMap<HackSetting, Double>(HackSetting.class);
        updated.putAll(values); updated.putAll(changes);
        return new HackSettings(updated);
    }
    public List<HackSetting> warnings() {
        return java.util.Arrays.stream(HackSetting.values()).filter(setting -> get(setting) > setting.warning).toList();
    }
}
