package me.advait.contender.nametag;

import me.advait.contender.Contender;
import me.advait.contender.core.Module;
import me.advait.contender.role.PlayerRole;
import me.advait.contender.role.RoleStyle;
import me.advait.contender.tier.TierFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.*;

/** Adds nametag teams to the scoreboards viewers already use, preserving their objectives. */
public final class NameTagManager extends Module {
    private record Tag(Component prefix, NamedTextColor color) {
        Component name(String name) { return prefix.append(Component.text(name, color)); }
    }
    private record Entry(String name, Team team, String previousTeam) { }
    private final Map<Scoreboard, Map<UUID, Entry>> boards = new IdentityHashMap<>();
    private long nextTeam;
    /** Who has their nametag hidden, by whoever asked (the Nameless sabotage, once per event or duel). */
    private final Map<Object, java.util.function.Predicate<UUID>> hiders = new IdentityHashMap<>();

    /** Whether this player's nametag is hidden right now. */
    public boolean hidden(UUID player) {
        for (var hider : hiders.values()) if (hider.test(player)) return true;
        return false;
    }

    /** Hides the nametags of the players {@code who} matches until {@link #show(Object)} with the same owner. */
    public void hide(Object owner, java.util.function.Predicate<UUID> who) {
        hiders.put(owner, who);
        refresh();
    }

    public void show(Object owner) {
        if (hiders.remove(owner) != null) refresh();
    }

    public NameTagManager(Contender plugin) { super(plugin); }
    @Override protected void onEnable() {
        refresh();
        refreshTiers();
        tasks.repeat(20L, 20L, this::refresh);
        tasks.repeat(1200L, 1200L, this::refreshTiers);
    }
    @Override protected void onDisable() {
        boards.forEach((board, entries) -> entries.values().forEach(entry -> remove(board, entry)));
        boards.clear();
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        tasks.later(1L, () -> { if (player.isOnline()) { refresh(); fetch(player); } });
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        boards.forEach((board, entries) -> {
            Entry entry = entries.remove(event.getPlayer().getUniqueId());
            if (entry != null) remove(board, entry);
        });
    }
    private void refreshTiers() { for (Player player : Bukkit.getOnlinePlayers()) fetch(player); }
    private void fetch(Player player) {
        plugin.getTierService().refresh(player.getUniqueId()).whenComplete((data, failure) -> {
            if (isEnabled() && player.isOnline()) refresh();
        });
    }
    public void refresh() {
        if (!isEnabled()) return;
        readStyles();
        var online = List.copyOf(Bukkit.getOnlinePlayers());
        Set<Scoreboard> used = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Player viewer : online) used.add(viewer.getScoreboard());
        for (Scoreboard board : new ArrayList<>(boards.keySet())) {
            if (!used.contains(board)) boards.remove(board).values().forEach(entry -> remove(board, entry));
        }
        Set<UUID> present = new HashSet<>();
        online.forEach(player -> present.add(player.getUniqueId()));
        Map<UUID, Tag> tags = new HashMap<>();
        for (Player player : online) tags.put(player.getUniqueId(), tag(player));
        for (Scoreboard board : used) {
            Map<UUID, Entry> entries = boards.computeIfAbsent(board, ignored -> new HashMap<>());
            for (UUID id : new ArrayList<>(entries.keySet())) if (!present.contains(id)) remove(board, entries.remove(id));
            for (Player player : online) {
                Entry entry = entries.get(player.getUniqueId());
                if (entry == null || entry.team().getScoreboard() == null || !entry.team().equals(board.getEntryTeam(player.getName()))) {
                    if (entry != null) remove(board, entry);
                    Team previous = board.getEntryTeam(player.getName());
                    String name;
                    do { name = "ct_" + Long.toHexString(nextTeam++); } while (board.getTeam(name) != null);
                    Team team = board.registerNewTeam(name);
                    team.setAllowFriendlyFire(true);
                    team.setCanSeeFriendlyInvisibles(false);
                    if (previous != null) {
                        for (Team.Option option : Team.Option.values()) team.setOption(option, previous.getOption(option));
                        team.suffix(previous.suffix());
                    }
                    team.addEntry(player.getName());
                    entry = new Entry(player.getName(), team, previous == null ? null : previous.getName());
                    entries.put(player.getUniqueId(), entry);
                }
                Team.OptionStatus visibility = hidden(player.getUniqueId()) ? Team.OptionStatus.NEVER : Team.OptionStatus.ALWAYS;
                if (entry.team().getOption(Team.Option.NAME_TAG_VISIBILITY) != visibility) entry.team().setOption(Team.Option.NAME_TAG_VISIBILITY, visibility);
                Tag tag = tags.get(player.getUniqueId());
                if (!tag.prefix().equals(entry.team().prefix())) entry.team().prefix(tag.prefix());
                if (!entry.team().hasColor() || !tag.color().equals(entry.team().color())) entry.team().color(tag.color());
            }
        }
    }
    private Tag tag(Player player) {
        return tag(player.getUniqueId());
    }
    private Tag tag(UUID id) {
        PlayerRole role = plugin.getRoleManager().getRole(id);
        if (role == PlayerRole.CONTESTANT) return new Tag(TierFormatter.prefix(plugin.getTierService().cached(id)), NamedTextColor.WHITE);
        RoleStyle style = styles.get(role);
        if (style == null) style = RoleStyle.read(plugin.getConfig(), role);
        return new Tag(style.prefixComponent(), style.color());
    }

    // Read from config on the server thread; chat formatting reads it from the async chat thread.
    private volatile Map<PlayerRole, RoleStyle> styles = Map.of();

    private void readStyles() {
        Map<PlayerRole, RoleStyle> read = new EnumMap<>(PlayerRole.class);
        for (PlayerRole role : PlayerRole.values()) if (role != PlayerRole.CONTESTANT) read.put(role, RoleStyle.read(plugin.getConfig(), role));
        styles = read;
    }
    public Component displayName(Player player) { return displayName(player.getUniqueId(), player.getName()); }
    public Component displayName(UUID id, String name) { return tag(id).name(name); }
    private void remove(Scoreboard board, Entry entry) {
        boolean restore = entry.team().equals(board.getEntryTeam(entry.name()));
        if (entry.team().getScoreboard() != null) entry.team().unregister();
        if (restore && entry.previousTeam() != null) {
            Team previous = board.getTeam(entry.previousTeam());
            if (previous != null) previous.addEntry(entry.name());
        }
    }
}
