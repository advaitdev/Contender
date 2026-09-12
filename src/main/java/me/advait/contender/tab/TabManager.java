package me.advait.contender.tab;

import com.destroystokyo.paper.profile.ProfileProperty;
import me.advait.contender.Contender;
import me.advait.contender.game.AbstractGameState;
import me.advait.contender.minigame.MinigameLayout;
import me.advait.contender.minigame.MinigameMode;
import me.advait.contender.race.RaceRun;
import me.advait.contender.tournament.Tournament;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.*;
import java.util.logging.Level;

/** Owns tab presentation only; player visibility, scoreboards, and eligibility stay with their managers. */
public final class TabManager extends AbstractGameState {
    public record Event(UUID id, String name, String status, String caption, boolean minigame, boolean cancelled) { }
    private record Profile(String name, String texture, String signature) { }
    private static final class Viewer {
        final Component header, footer;
        final Set<UUID> unlisted = new HashSet<>();
        List<TabRow> rows;
        Viewer(Player player) { header = player.playerListHeader(); footer = player.playerListFooter(); }
    }
    private final Contender contender;
    private final TabBridge bridge;
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private final Map<UUID, BracketLayout.View> views = new HashMap<>();
    private final Map<UUID, Profile> profiles = new LinkedHashMap<>();
    private UUID tournamentId;
    private boolean failed;

    public TabManager(Contender plugin) { this(plugin, new ForkTabBridge()); }
    public TabManager(Contender plugin, TabBridge bridge) { super(plugin); contender = plugin; this.bridge = bridge; }
    @Override protected void onEnable() {
        if (!bridge.available()) contender.getLogger().warning("Tab brackets require UHCR Paper's UhcrTabEntries API. The normal player list will remain available.");
        refresh();
        runRepeating(this::refresh, 20, 20);
    }
    @Override protected void onDisable() { restoreAll(); profiles.clear(); views.clear(); }
    public boolean supportsBracket() { return bridge.available() && !failed; }
    public BracketLayout.View view(Player player) { return views.getOrDefault(player.getUniqueId(), BracketLayout.View.following()); }
    public void setView(Player player, BracketLayout.View view) {
        views.put(player.getUniqueId(), view);
        refresh();
    }
    @EventHandler(priority = EventPriority.MONITOR) public void onJoin(PlayerJoinEvent event) {
        runLater(this::refresh, 1);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void onQuit(PlayerQuitEvent event) {
        remember(event.getPlayer());
        viewers.remove(event.getPlayer().getUniqueId());
        views.remove(event.getPlayer().getUniqueId());
        bridge.quit(event.getPlayer());
        runLater(this::refresh, 1);
    }
    public void refresh() {
        if (!isEnabled()) return;
        if (failed) { restoreAll(); return; }
        try {
            TabStyle style = TabStyle.read(contender.getConfig());
            if (!style.enabled()) { restoreAll(); return; }
            Event event = event();
            if (event != null && event.cancelled()) {
                restoreAll(); views.clear(); return;
            }
            UUID current = event == null ? null : event.id();
            if (!Objects.equals(current, tournamentId)) {
                profiles.clear(); views.clear(); tournamentId = current;
            }
            for (Player player : plugin.getServer().getOnlinePlayers()) remember(player);
            Map<BracketLayout.View, BracketLayout.Layout> layouts = new HashMap<>();
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                Viewer state = viewers.computeIfAbsent(player.getUniqueId(), ignored -> new Viewer(player));
                Component header = style.header(event == null ? null : event.name());
                if (!Objects.equals(player.playerListHeader(), header)) player.sendPlayerListHeader(header);
                if (event == null || !supportsBracket()) {
                    restoreRows(player, state);
                    Component footer = event == null ? Component.empty() : Component.text(event.status(), NamedTextColor.GRAY);
                    if (!Objects.equals(player.playerListFooter(), footer)) player.sendPlayerListFooter(footer);
                    continue;
                }
                BracketLayout.Layout layout = layouts.computeIfAbsent(view(player), ignored -> layout(player));
                // Keep real profiles available for skins and signed chat, but draw our display rows in tab.
                for (Player target : plugin.getServer().getOnlinePlayers()) {
                    if (player.isListed(target)) {
                        state.unlisted.add(target.getUniqueId());
                        player.unlistPlayer(target);
                    }
                }
                if (!layout.rows().equals(state.rows)) {
                    // Set first so a partial transport failure still triggers cleanup.
                    state.rows = layout.rows();
                    bridge.show(player, layout.rows());
                }
                Component footer = Component.text((event.minigame() ? event.caption() : layout.heading()) + " | " + event.status(), NamedTextColor.GRAY);
                if (!Objects.equals(player.playerListFooter(), footer)) player.sendPlayerListFooter(footer);
            }
        } catch (RuntimeException failure) {
            failed = true;
            restoreAll();
            contender.getLogger().log(Level.SEVERE, "Could not render the tournament tab list; restored the normal player list.", failure);
        }
    }
    private MinigameMode selectedMinigame() {
        return contender.getMinigameManager() == null ? null : contender.getMinigameManager().selected();
    }
    private RaceRun displayedRace(MinigameMode mode) {
        if (contender.getRaceManager() == null) return null;
        if (contender.getMinigameManager() == null) return contender.getRaceManager().displayed();
        return mode != null && mode.id().equals("mace_race") ? contender.getRaceManager().current() : null;
    }
    public Event event() {
        MinigameMode mode = selectedMinigame();
        if (mode != null) return new Event(mode.eventId(), mode.eventName(), mode.statusText(),
                mode.id().equals("mace_race") ? "Finish Times" : "Leaderboard", true, mode.cancelled());
        RaceRun race = displayedRace(null);
        if (race != null) return new Event(race.id(), race.name(), race.statusText(), "Finish Times", true, race.state() == RaceRun.State.CANCELLED);
        Tournament tournament = contender.getTournamentManager().current();
        return tournament == null ? null : new Event(tournament.id(), tournament.name(), tournament.statusText(), "Bracket", false, tournament.isCancelled());
    }
    public BracketLayout.Layout layout(Player viewer) {
        return layout(view(viewer));
    }
    public BracketLayout.Layout layout(BracketLayout.View view) {
        MinigameMode mode = selectedMinigame();
        if (mode != null && mode.cancelled()) return null;
        RaceRun race = displayedRace(mode);
        if (race != null) return race.state() == RaceRun.State.CANCELLED ? null : me.advait.contender.race.RaceLayout.render(race, r -> presence(r.id(), null, r.name()), System.nanoTime());
        if (mode != null) return MinigameLayout.render(mode.displayName(), mode.standings(), row -> presence(row.playerId(), null, row.name()));
        Tournament tournament = contender.getTournamentManager().current();
        if (tournament == null || tournament.isCancelled()) return null;
        Map<Integer, BracketLayout.Score> scores = new HashMap<>();
        contender.getTournamentManager().playing().forEach((number, duel) -> scores.put(number,
                new BracketLayout.Score(duel.getTeam1().getScore(), duel.getTeam2().getScore())));
        LinkedHashSet<UUID> roster = new LinkedHashSet<>();
        tournament.entries().forEach(entry -> roster.addAll(entry.players()));
        roster.addAll(profiles.keySet());
        return BracketLayout.render(tournament, scores, id -> presence(id, tournament), List.copyOf(roster), view);
    }
    private BracketLayout.Presence presence(UUID id, Tournament tournament) { return presence(id, tournament, null); }
    private BracketLayout.Presence presence(UUID id, Tournament tournament, String fallback) {
        Player online = plugin.getServer().getPlayer(id);
        Profile profile = profiles.get(id);
        if (profile == null) {
            var offline = plugin.getServer().getOfflinePlayer(id);
            String name = offline.getName();
            if (name == null) name = fallback;
            if (name == null && tournament != null) name = tournament.entries().stream().map(e -> e.playerNames().get(id))
                    .filter(Objects::nonNull).findFirst().orElse(null);
            if (name == null && tournament != null) name = tournament.entries().stream().filter(e -> e.players().size() == 1 && e.players().contains(id))
                    .map(e -> e.name()).findFirst().orElse(id.toString().substring(0, 8));
            if (name == null) name = id.toString().substring(0, 8);
            profile = profile(offline, name);
            // Cache offline identities once; a join replaces them with the signed skin.
            profiles.put(id, profile);
        }
        Component name = contender.getNameTagManager().displayName(id, profile.name());
        return new BracketLayout.Presence(profile.name(), name, online == null ? -1 : Math.max(0, online.getPing()), profile.texture(), profile.signature(),
                me.advait.contender.util.StringUtil.resolvedHead(id, profile.name(), profile.texture() == null ? List.of()
                        : List.of(new ProfileProperty("textures", profile.texture(), profile.signature()))));
    }
    private void remember(Player player) { profiles.put(player.getUniqueId(), profile(player, player.getName())); }
    private Profile profile(org.bukkit.OfflinePlayer player, String name) {
        String texture = null, signature = null;
        for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
            if (property.getName().equals("textures")) { texture = property.getValue(); signature = property.getSignature(); break; }
        }
        return new Profile(name, texture, signature);
    }
    private void restoreRows(Player viewer, Viewer state) {
        if (state.rows != null) { bridge.clear(viewer); state.rows = null; }
        for (UUID id : new HashSet<>(state.unlisted)) {
            Player target = plugin.getServer().getPlayer(id);
            if (target == null) { state.unlisted.remove(id); continue; }
            if (viewer.canSee(target)) {
                if (!viewer.isListed(target)) viewer.listPlayer(target);
                state.unlisted.remove(id);
            }
        }
    }
    private void restoreAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Viewer state = viewers.get(player.getUniqueId());
            if (state == null) continue;
            try {
                restoreRows(player, state);
                player.sendPlayerListHeaderAndFooter(state.header == null ? Component.empty() : state.header,
                        state.footer == null ? Component.empty() : state.footer);
                if (state.unlisted.isEmpty()) viewers.remove(player.getUniqueId());
            } catch (RuntimeException failure) {
                contender.getLogger().log(Level.WARNING, "Could not restore tab for " + player.getName(), failure);
            }
        }
    }
}
