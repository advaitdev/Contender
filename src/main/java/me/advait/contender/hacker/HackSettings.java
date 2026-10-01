package me.advait.contender.hacker;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** A full set of hack values. Out-of-range values (for example from an older save) are clamped. */
public record HackSettings(Map<HackSetting, Double> values) {
    public HackSettings {
        var checked = new EnumMap<HackSetting, Double>(HackSetting.class);
        for (var setting : HackSetting.values()) {
            double value = values.getOrDefault(setting, setting.normal);
            checked.put(setting, Double.isFinite(value) ? Math.clamp(value, setting.min, setting.max) : setting.normal);
        }
        values = Map.copyOf(checked);
    }

    public static HackSettings defaults() { return new HackSettings(Map.of()); }

    public double get(HackSetting setting) { return values.get(setting); }

    public HackSettings with(HackSetting setting, double value) {
        var updated = new EnumMap<HackSetting, Double>(HackSetting.class);
        updated.putAll(values);
        updated.put(setting, setting.validate(value));
        return new HackSettings(updated);
    }

    public HackSettings with(Map<HackSetting, Double> changes) {
        var updated = new EnumMap<HackSetting, Double>(HackSetting.class);
        updated.putAll(values);
        updated.putAll(changes);
        return new HackSettings(updated);
    }

    /** Every hack at one preset strength. */
    public static HackSettings all(HackSetting.Level level) {
        var values = new EnumMap<HackSetting, Double>(HackSetting.class);
        for (var setting : HackSetting.values()) values.put(setting, setting.value(level));
        return new HackSettings(values);
    }

    /** A believable "closet cheater" mix: small edges that are hard to spot on camera. */
    public static HackSettings closet() {
        return defaults().with(Map.of(
                HackSetting.REACH, HackSetting.REACH.value(HackSetting.Level.SUBTLE),
                HackSetting.ANTI_KNOCKBACK, HackSetting.ANTI_KNOCKBACK.value(HackSetting.Level.SUBTLE),
                HackSetting.ATTACK_SPEED, HackSetting.ATTACK_SPEED.value(HackSetting.Level.SUBTLE),
                HackSetting.RESISTANCE, HackSetting.RESISTANCE.value(HackSetting.Level.SUBTLE)));
    }

    public List<HackSetting> active() {
        return Arrays.stream(HackSetting.values()).filter(setting -> !setting.isOff(get(setting))).toList();
    }

    public List<HackSetting> warnings() {
        return Arrays.stream(HackSetting.values()).filter(setting -> get(setting) > setting.warning + 1e-9).toList();
    }

    /** "Reach 3.3 blocks, Speed 1.06x", or "No hacks". */
    public String summary() {
        List<HackSetting> active = active();
        if (active.isEmpty()) return "No hacks";
        return String.join(", ", active.stream().map(setting -> setting.label + " " + setting.display(get(setting))).toList());
    }
}
