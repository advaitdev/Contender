package me.advait.contender.minigame;

import net.kyori.adventure.text.format.NamedTextColor;
import java.util.UUID;

/** Rows arrive in the mode's ranking order. UUIDs preserve heads and tags for offline players. */
public record MinigameStanding(UUID playerId, String name, String value, NamedTextColor color) { }
