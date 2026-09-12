package me.advait.contender.tab;

import net.kyori.adventure.text.Component;

/** Shared row content. Board rows carry an optional match target; tab uses native profile heads. */
public record TabRow(Component text, int latency, String texture, String signature, Component boardText, Integer matchNumber) {
    public TabRow(Component text, int latency, String texture, String signature) { this(text, latency, texture, signature, text, null); }
    public static TabRow label(Component text) { return new TabRow(text, 1000, TabSkin.SPACER, TabSkin.SIGNATURE); }
    public TabRow match(int number) { return new TabRow(text, latency, texture, signature, boardText, number); }
}
