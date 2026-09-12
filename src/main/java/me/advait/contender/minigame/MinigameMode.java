package me.advait.contender.minigame;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import java.util.List;
import java.util.UUID;

/** Common event operations; each mode owns its rules, persistence, and runtime state. */
public interface MinigameMode {
    String id();
    String displayName();
    UUID eventId();
    String eventName();
    String statusText();
    boolean terminal();
    boolean cancelled();
    boolean active();
    boolean busy();
    boolean isReserved(UUID id);
    boolean owns(UUID id);
    boolean isPlaying(UUID id);
    boolean isSpectator(UUID id);
    boolean pendingReturn(UUID id);
    void withdraw(UUID id);
    /** Cancels even an interrupted event and releases its runtime ownership. */
    void forceCancel();
    default boolean leaveSpectating(Player player) { return false; }
    boolean restore(Player player);
    void open(Player player);
    void createDialog(Player player);
    void setupDialog(Player player);
    List<MinigameStanding> standings();
    boolean inArena(Location location);
}
