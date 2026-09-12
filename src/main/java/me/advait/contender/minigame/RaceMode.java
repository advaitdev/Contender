package me.advait.contender.minigame;

import me.advait.contender.Contender;
import me.advait.contender.dialog.RaceDialogs;
import me.advait.contender.race.RaceRun;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.*;

/** Adapts the original race manager to the shared event coordinator. */
public final class RaceMode implements MinigameMode {
    private final Contender plugin;
    public RaceMode(Contender plugin) { this.plugin = plugin; }
    private me.advait.contender.race.RaceManager manager() { return plugin.getRaceManager(); }
    public String id() { return "mace_race"; }
    public String displayName() { return "Mace Race"; }
    public UUID eventId() { return manager().current() == null ? null : manager().current().id(); }
    public String eventName() { return manager().current() == null ? "Mace Race" : manager().current().name(); }
    public String statusText() { return manager().current() == null ? "Not Created" : manager().current().statusText(); }
    public boolean terminal() { return manager().current() == null || manager().current().terminal(); }
    public boolean cancelled() { return manager().current() != null && manager().current().state() == RaceRun.State.CANCELLED; }
    public boolean active() { return manager().active(); }
    public boolean busy() { return manager().busy(); }
    public boolean isReserved(UUID id) { return manager().isReserved(id); }
    public boolean owns(UUID id) { return manager().owns(id); }
    public boolean isPlaying(UUID id) { return manager().isRacing(id); }
    public boolean isSpectator(UUID id) { return owns(id) && manager().current().racer(id).done(); }
    public boolean pendingReturn(UUID id) { return manager().pendingReturn(id); }
    public void withdraw(UUID id) { manager().withdraw(id); }
    public void forceCancel() { manager().forceCancel(); }
    public boolean restore(Player player) { return manager().restore(player); }
    public void open(Player player) { new RaceDialogs(plugin).open(player); }
    public void createDialog(Player player) { new RaceDialogs(plugin).create(player); }
    public void setupDialog(Player player) { new RaceDialogs(plugin).courses(player, 0); }
    public boolean inArena(Location location) { return manager().inArena(location); }
    public List<MinigameStanding> standings() {
        if (manager().current() == null) return List.of();
        return manager().current().standings().stream().map(r -> new MinigameStanding(r.id(), r.name(),
                r.finishNanos() >= 0 ? RaceRun.time(r.finishNanos()) : r.withdrawn() ? "DNF" : "#" + r.checkpoint(),
                r.finishNanos() >= 0 ? NamedTextColor.GREEN : NamedTextColor.GRAY)).toList();
    }
}
