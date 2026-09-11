package me.advait.contender.role;

import java.util.Locale;

public enum PlayerRole {
    DIRECTOR("Director"), SPECTATOR("Spectator"), CONTESTANT("Contestant");

    private final String label;
    PlayerRole(String label) { this.label = label; }
    public String label() { return label; }
    public String id() { return name().toLowerCase(Locale.ROOT); }
    public static PlayerRole parse(String text) {
        try { return valueOf(text.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Choose director, spectator, or contestant."); }
    }
}
