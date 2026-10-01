package me.advait.contender.tab;

import com.destroystokyo.paper.profile.ProfileProperty;
import me.advait.contender.Contender;
import me.advait.contender.core.Module;
import me.advait.contender.stage.Stage;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.logging.Level;

/**
 * Draws the current stage in the tab list (on UHCR Paper, which can show custom rows) and keeps the
 * header and footer up to date everywhere. Restores the normal list when nothing is selected.
 */
public final class TabManager extends Module {
    private record Profile(String name, String texture, String signature) { }
    private static final class Viewer {
        final Component header, footer;
        final Set<UUID> unlisted = new HashSet<>();
        List<TabRow> rows;
        Viewer(Player player) { header = player.playerListHeader(); footer = player.playerListFooter(); }
    }

    private final TabBridge bridge;
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private final Map<UUID, BracketLayout.View> views = new HashMap<>();
    private final Map<UUID, Profile> profiles = new LinkedHashMap<>();
    private UUID stageId;
    private boolean failed;

    public TabManager(Contender plugin) { this(plugin, new ForkTabBridge()); }
    public TabManager(Contender plugin, TabBridge bridge) { super(plugin); this.bridge = bridge; }

    @Override protected void onEnable() {
        if (!bridge.available()) plugin.getLogger().info("Custom tab rows need UHCR Paper; the normal player list will be used. /bracket still shows the bracket.");
        refresh();
        tasks.repeat(20, 20, this::refresh);
    }

    @Override protected void onDisable() { restoreAll(); profiles.clear(); views.clear(); }

    public boolean supportsBracket() { return bridge.available() && !failed; }
    public BracketLayout.View view(Player player) { return views.getOrDefault(player.getUniqueId(), BracketLayout.View.following()); }

    public void setView(Player player, BracketLayout.View view) {
        views.put(player.getUniqueId(), view);
        refresh();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { tasks.later(1, this::refresh); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        remember(event.getPlayer());
        viewers.remove(event.getPlayer().getUniqueId());
        views.remove(event.getPlayer().getUniqueId());
        try { bridge.quit(event.getPlayer()); }
        catch (RuntimeException failure) { plugin.getLogger().log(Level.FINE, "Tab bridge quit failed", failure); }
        tasks.later(1, this::refresh);
    }

    private Stage visibleStage() {
        Stage stage = plugin.getStages().current();
        return stage == null || stage.cancelled() ? null : stage;
    }

    public void refresh() {
        if (!isEnabled()) return;
        if (failed) { restoreAll(); return; }
        try {
            TabStyle style = TabStyle.read(plugin.getConfig());
            if (!style.enabled()) { restoreAll(); return; }
            Stage stage = visibleStage();
            UUID current = stage == null ? null : stage.id();
            if (!Objects.equals(current, stageId)) { profiles.clear(); views.clear(); stageId = current; }
            for (Player player : plugin.getServer().getOnlinePlayers()) remember(player);
            Map<BracketLayout.View, BracketLayout.Layout> layouts = new HashMap<>();
            Component header = style.header(stage == null ? null : stage.name());
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                Viewer state = viewers.computeIfAbsent(player.getUniqueId(), ignored -> new Viewer(player));
                if (!Objects.equals(player.playerListHeader(), header)) player.sendPlayerListHeader(header);
                if (stage == null || !supportsBracket()) {
                    restoreRows(player, state);
                    Component footer = stage == null ? Component.empty()
                            : Component.text(stage.kind() + " | " + stage.statusText(), plugin.getThemes().current().muted());
                    if (!Objects.equals(player.playerListFooter(), footer)) player.sendPlayerListFooter(footer);
                    continue;
                }
                BracketLayout.Layout layout = layouts.computeIfAbsent(view(player), this::layout);
                if (layout == null) { restoreRows(player, state); continue; }
                for (Player target : plugin.getServer().getOnlinePlayers()) {
                    if (player.isListed(target)) {
                        state.unlisted.add(target.getUniqueId());
                        player.unlistPlayer(target);
                    }
                }
                if (!layout.rows().equals(state.rows)) {
                    state.rows = layout.rows();
                    bridge.show(player, layout.rows());
                }
                Component footer = Component.text(stage.caption(layout) + " | " + stage.statusText(), plugin.getThemes().current().muted());
                if (!Objects.equals(player.playerListFooter(), footer)) player.sendPlayerListFooter(footer);
            }
        } catch (RuntimeException failure) {
            failed = true;
            restoreAll();
            plugin.getLogger().log(Level.SEVERE, "Could not draw the tab bracket; showing the normal player list instead.", failure);
        }
    }

    /** The current stage's rows for a view, shared by the tab list, the board and /bracket. */
    public BracketLayout.Layout layout(BracketLayout.View view) {
        Stage stage = visibleStage();
        if (stage == null) return null;
        List<UUID> others = new ArrayList<>();
        for (UUID id : profiles.keySet()) if (!stage.involves(id)) others.add(id);
        return stage.layout(view, id -> presence(id, null), others);
    }

    public BracketLayout.Layout layout(Player viewer) { return layout(view(viewer)); }

    /** How a player appears in rows: their tag, head, ping, and skin. Offline players show an X. */
    public BracketLayout.Presence presence(UUID id, String fallback) {
        Player online = plugin.getServer().getPlayer(id);
        Profile profile = profiles.get(id);
        if (profile == null) {
            var offline = plugin.getServer().getOfflinePlayer(id);
            String name = offline.getName() != null ? offline.getName() : fallback != null ? fallback : id.toString().substring(0, 8);
            profile = profile(offline, name);
            profiles.put(id, profile);
        }
        Component name = plugin.getNameTagManager().displayName(id, profile.name());
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
        if (state.rows != null) {
            try { bridge.clear(viewer); } catch (RuntimeException failure) { plugin.getLogger().log(Level.FINE, "Tab clear failed", failure); }
            state.rows = null;
        }
        for (UUID id : new HashSet<>(state.unlisted)) {
            Player target = plugin.getServer().getPlayer(id);
            // Paper keeps them unlisted for this viewer even after a rejoin; list them again once they're back.
            if (target == null) continue;
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
                plugin.getLogger().log(Level.WARNING, "Could not restore tab for " + player.getName(), failure);
            }
        }
    }
}
