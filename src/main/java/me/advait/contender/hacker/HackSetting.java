package me.advait.contender.hacker;

import me.advait.contender.dialog.DialogIcon;
import java.math.BigDecimal;
import java.util.Locale;

/** Values shown in the editor; multipliers include the held weapon's attributes. */
public enum HackSetting {
    REACH("Reach", "blocks", DialogIcon.REACH, 3, 64, 3, 3.5),
    ATTACK_SPEED("Attack Speed", "x", DialogIcon.REFRESH, 1, 10, 1, 1.5),
    ATTACK_DAMAGE("Attack Damage", "x", DialogIcon.DUEL, 1, 10, 1, 1.5),
    RESISTANCE("Resistance", "%", DialogIcon.ARMOR, 0, 100, 0, 30),
    ANTI_KNOCKBACK("Anti-Knockback", "%", DialogIcon.ANCHOR, 0, 100, 0, 40),
    MOVEMENT_SPEED("Movement Speed", "x", DialogIcon.BOOTS, 1, 5, 1, 1.2),
    JUMP_STRENGTH("Jump Strength", "x", DialogIcon.JUMP, 1, 5, 1, 1.2),
    STEP_HEIGHT("Step Height", "blocks", DialogIcon.STEP, 0.6, 3, 0.6, 0.9);

    public final String label, unit;
    public final DialogIcon icon;
    public final double min, max, normal, warning;
    HackSetting(String label, String unit, DialogIcon icon, double min, double max, double normal, double warning) {
        this.label = label; this.unit = unit; this.icon = icon;
        this.min = min; this.max = max; this.normal = normal; this.warning = warning;
    }
    public String key() { return name().toLowerCase(Locale.ROOT); }
    public double validate(double value) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(label + " must be from " + number(min) + " to " + number(max) + ".");
        }
        return value;
    }
    public String display(double value) { return number(value) + (unit.equals("blocks") ? " blocks" : unit); }
    public static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
}
