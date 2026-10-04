package me.advait.contender.hacker;

import me.advait.contender.dialog.DialogIcon;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * One hack. Each has four preset strengths; values above {@code warning} start to look suspicious.
 * Multipliers apply on top of the held weapon and any kit attributes.
 */
public enum HackSetting {
    REACH("Reach", "How far you can hit players.", "blocks", DialogIcon.REACH, 3, 8, 3, 3.5, 3.2, 3.6, 4.2, 6),
    ATTACK_SPEED("Attack Speed", "How quickly your weapon recharges.", "x", DialogIcon.REFRESH, 1, 5, 1, 1.3, 1.15, 1.35, 1.75, 3),
    ATTACK_DAMAGE("Damage", "How hard your melee hits land.", "x", DialogIcon.DUEL, 1, 4, 1, 1.25, 1.1, 1.25, 1.5, 2.5),
    RESISTANCE("Resistance", "Takes a share off every hit you receive.", "%", DialogIcon.ARMOR, 0, 90, 0, 25, 10, 20, 35, 60),
    ANTI_KNOCKBACK("Anti-Knockback", "Reduces how far hits push you.", "%", DialogIcon.ANCHOR, 0, 100, 0, 40, 25, 50, 75, 100),
    MOVEMENT_SPEED("Speed", "How fast you walk and sprint.", "x", DialogIcon.BOOTS, 1, 3, 1, 1.12, 1.06, 1.12, 1.25, 1.6),
    JUMP_STRENGTH("Jump Boost", "How high you jump.", "x", DialogIcon.JUMP, 1, 3, 1, 1.12, 1.06, 1.12, 1.25, 1.6),
    STEP_HEIGHT("Step Up", "Walk up blocks without jumping.", "blocks", DialogIcon.STEP, 0.6, 3, 0.6, 1, 0.8, 1.05, 1.6, 2.5),
    REGENERATION("Regeneration", "Heals you slowly, even mid-fight.", "hp/10s", DialogIcon.HEART, 0, 10, 0, 2, 1, 2, 4, 10),
    NO_FALL("No Fall", "Reduces fall damage.", "%", DialogIcon.SLIME, 0, 100, 0, 50, 30, 50, 80, 100);

    /** Preset strengths, in order. */
    public enum Level {
        OFF("Off"), SUBTLE("Subtle"), NOTICEABLE("Noticeable"), STRONG("Strong"), BLATANT("Blatant");
        public final String label;
        Level(String label) { this.label = label; }
    }

    public final String label, description, unit;
    public final DialogIcon icon;
    public final double min, max, normal, warning;
    private final double[] presets;

    HackSetting(String label, String description, String unit, DialogIcon icon, double min, double max, double normal, double warning,
                double subtle, double noticeable, double strong, double blatant) {
        this.label = label;
        this.description = description;
        this.unit = unit;
        this.icon = icon;
        this.min = min;
        this.max = max;
        this.normal = normal;
        this.warning = warning;
        this.presets = new double[]{normal, subtle, noticeable, strong, blatant};
    }

    public String key() { return name().toLowerCase(Locale.ROOT); }

    public double value(Level level) { return presets[level.ordinal()]; }

    /** The preset matching a value exactly, or null for a custom value. */
    public Level levelOf(double value) {
        for (Level level : Level.values()) if (Math.abs(presets[level.ordinal()] - value) < 1e-6) return level;
        return null;
    }

    public List<Level> levels() { return List.of(Level.values()); }

    public boolean isOff(double value) { return Math.abs(value - normal) < 1e-6; }

    public double validate(double value) {
        if (!Double.isFinite(value) || value < min - 1e-6 || value > max + 1e-6) {
            throw new IllegalArgumentException(label + " must be from " + number(min) + " to " + number(max) + ".");
        }
        return Math.clamp(value, min, max);
    }

    public double step() {
        return switch (this) {
            case RESISTANCE, ANTI_KNOCKBACK, NO_FALL -> 1;
            case REGENERATION -> 0.5;
            case MOVEMENT_SPEED, JUMP_STRENGTH -> 0.01;
            default -> 0.05;
        };
    }

    public String display(double value) {
        if (isOff(value)) return "Off";
        return switch (unit) {
            case "x" -> number(value) + "x";
            case "%" -> number(value) + "%";
            case "blocks" -> number(value) + " blocks";
            default -> number(value) + " " + unit;
        };
    }

    public static String number(double value) {
        return BigDecimal.valueOf(Math.round(value * 100) / 100.0).stripTrailingZeros().toPlainString();
    }
}
