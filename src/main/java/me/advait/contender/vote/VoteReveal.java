package me.advait.contender.vote;

import me.advait.contender.Contender;
import me.advait.contender.activity.Activity;
import me.advait.contender.activity.ActivityRegistry;
import me.advait.contender.core.Msg;
import me.advait.contender.core.Sounds;
import me.advait.contender.core.Tasks;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.display.Holograms;
import me.advait.contender.display.Theme;
import me.advait.contender.role.PlayerRole;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * The results ceremony. Candidates stand in a circle around the vote stage, every tally counts up at once,
 * then a spotlight circles the ring, slows down and stops on whoever was voted out.
 * Without a saved stage the same reveal plays wherever the candidates are standing.
 */
final class VoteReveal implements Activity, Listener {
    private static final int COUNT_STEP = 12;
    private final Contender plugin;
    private final VoteService service;
    private final VoteSession session;
    private final VoteBadges badges;
    private final boolean eliminate;
    private final Tasks tasks;
    private final Set<UUID> gathered = new LinkedHashSet<>();
    private final Map<UUID, Location> spots = new HashMap<>();
    private final List<Display> displays = new ArrayList<>();
    private boolean moving;
    private UUID votedOut;
    private boolean finished;

    VoteReveal(Contender plugin, VoteService service, VoteSession session, VoteBadges badges, boolean eliminate) {
        this.plugin = plugin;
        this.service = service;
        this.session = session;
        this.badges = badges;
        this.eliminate = eliminate;
        this.tasks = new Tasks(plugin, "Vote reveal");
    }

    @Override public String displayName() { return "the vote reveal"; }

    void play() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        List<VoteSession.Tally> results = session.results();
        Location stage = service.stageLocation();
        if (stage != null) gather(stage, results);
        Theme theme = plugin.getThemes().current();
        Location headline = stage != null ? stage.clone().add(0, 4.2, 0) : null;
        if (headline != null) {
            TextDisplay title = Holograms.text(headline, Component.text("The votes are in", theme.primary()), 0.01f,
                    Display.Billboard.CENTER, theme.background(0), "vote_reveal");
            displays.add(title);
            tasks.later(2, () -> Holograms.animate(title, Holograms.scaled(3.2f), 10));
        }
        Msg.title(onlineAll(), Component.text("The votes are in", theme.primary()), Msg.text(session.totalVotes() + (session.totalVotes() == 1 ? " vote" : " votes") + " cast", DialogPalette.MUTED), 8, 50, 10);
        Sounds.REVEAL.playAll();
        for (VoteSession.Tally tally : results) badges.showCount(tally.candidate(), 0);
        int top = results.isEmpty() ? 0 : results.getFirst().votes();
        for (int step = 1; step <= top; step++) {
            int value = step;
            tasks.later(40L + (long) step * COUNT_STEP, () -> countStep(results, value, top));
        }
        long afterCount = 40L + (long) top * COUNT_STEP + 30;
        List<VoteSession.Candidate> leaders = session.leaders();
        if (leaders.isEmpty()) {
            tasks.later(afterCount, () -> conclude(List.of(), 0));
            return;
        }
        if (stage != null && gathered.size() > 1) tasks.later(afterCount, () -> spotlight(leaders, top));
        else tasks.later(afterCount, () -> conclude(leaders, top));
    }

    private void gather(Location stage, List<VoteSession.Tally> results) {
        List<VoteSession.Candidate> present = new ArrayList<>();
        for (VoteSession.Tally tally : results) {
            Player player = Bukkit.getPlayer(tally.candidate().id());
            if (player == null || player.isDead() || !plugin.getRegistry().isFree(player.getUniqueId())) continue;
            present.add(tally.candidate());
        }
        present.sort(Comparator.comparingInt(VoteSession.Candidate::number));
        double radius = Math.max(3.5, present.size() * 0.75);
        for (int i = 0; i < present.size(); i++) {
            double angle = Math.PI * 2 * i / present.size() - Math.PI / 2;
            Location spot = stage.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            Vector facing = stage.toVector().subtract(spot.toVector()).setY(0);
            spot.setDirection(facing.lengthSquared() < 1e-6 ? new Vector(0, 0, 1) : facing);
            spot.setPitch(0);
            Player player = Bukkit.getPlayer(present.get(i).id());
            try {
                plugin.getRegistry().claim(player.getUniqueId(), this, ActivityRegistry.Involvement.PLAYING);
            } catch (IllegalStateException busy) { continue; }
            gathered.add(player.getUniqueId());
            spots.put(player.getUniqueId(), spot);
            moving = true;
            try { player.teleport(spot); } finally { moving = false; }
        }
        plugin.getCelebrations().ring(stage.clone().add(0, 0.1, 0), radius, 64);
    }

    private void countStep(List<VoteSession.Tally> results, int value, int top) {
        for (VoteSession.Tally tally : results) {
            if (tally.votes() < value) continue;
            badges.showCount(tally.candidate(), value);
            badges.pop(tally.candidate().id(), 1.6f);
        }
        for (Player player : onlinePlayers()) Sounds.DRUM.play(player, 0.8f + 0.9f * value / Math.max(1, top));
    }

    /** A beam travels around the ring, slowing down, and stops on the voted-out player. */
    private void spotlight(List<VoteSession.Candidate> leaders, int top) {
        List<UUID> ring = new ArrayList<>();
        for (UUID id : gathered) if (Bukkit.getPlayer(id) != null) ring.add(id);
        if (ring.size() < 2) { conclude(leaders, top); return; }
        Theme theme = plugin.getThemes().current();
        UUID target = leaders.size() == 1 && ring.contains(leaders.getFirst().id()) ? leaders.getFirst().id() : null;
        // With a tie (or nobody here to land on) the beam would point at the wrong person.
        if (target == null) { conclude(leaders, top); return; }
        int targetIndex = ring.indexOf(target);
        int steps = ring.size() * 2 + targetIndex + 1;
        Location first = beamAt(ring.getFirst());
        BlockDisplay beam = Holograms.block(first, Material.WHITE_STAINED_GLASS.createBlockData(), Holograms.box(0.9f, 14f, 0.9f, 0), "vote_reveal");
        beam.setGlowing(true);
        beam.setGlowColorOverride(Color.fromRGB(theme.primary().value()));
        beam.setBrightness(new Display.Brightness(15, 15));
        beam.setTeleportDuration(3);
        displays.add(beam);
        long time = 0;
        for (int step = 0; step < steps; step++) {
            int index = step % ring.size();
            double progress = (double) step / steps;
            time += Math.round(3 + 14 * progress * progress);
            boolean last = step == steps - 1;
            tasks.later(time, () -> {
                Player player = Bukkit.getPlayer(ring.get(index));
                if (player != null) beam.teleport(beamAt(ring.get(index)));
                for (Player viewer : onlinePlayers()) Sounds.TICK.play(viewer, last ? 2f : 1.2f);
                if (last) tasks.later(16, () -> conclude(leaders, top));
            });
        }
    }

    private Location beamAt(UUID player) {
        Player online = Bukkit.getPlayer(player);
        Location spot = spots.getOrDefault(player, online == null ? null : online.getLocation());
        return spot == null ? null : spot.clone().add(0, -0.2, 0);
    }

    private void conclude(List<VoteSession.Candidate> leaders, int votes) {
        if (finished) return;
        Theme theme = plugin.getThemes().current();
        Collection<UUID> everyone = onlineAll();
        if (leaders.isEmpty()) {
            Msg.title(everyone, Component.text("No votes", theme.secondary()), Msg.text("Nobody was voted out", DialogPalette.MUTED), 5, 60, 15);
            Msg.broadcast(Msg.text("The vote ended with no votes cast.", DialogPalette.MUTED));
        } else if (leaders.size() > 1) {
            String names = String.join(" & ", leaders.stream().map(VoteSession.Candidate::name).toList());
            Msg.title(everyone, Component.text("It's a tie", theme.primary()), Msg.text(names + " · " + votes + (votes == 1 ? " vote" : " votes") + " each", DialogPalette.MUTED), 5, 70, 15);
            Msg.broadcast(Msg.text("The vote is tied between " + names + " with " + votes + (votes == 1 ? " vote" : " votes") + " each.", DialogPalette.ACCENT));
            for (VoteSession.Candidate leader : leaders) {
                badges.setText(leader.id(), Component.text(Integer.toString(leader.number()), theme.primary()).appendNewline()
                        .append(Component.text("Tied", DialogPalette.WARNING)));
                badges.pop(leader.id(), 1.9f);
            }
            Sounds.ANNOUNCE.playAll();
        } else {
            VoteSession.Candidate out = leaders.getFirst();
            Player player = Bukkit.getPlayer(out.id());
            if (player != null) {
                player.getWorld().strikeLightningEffect(player.getLocation());
                plugin.getCelebrations().fireworks(player.getLocation(), 4);
            }
            badges.setText(out.id(), Component.text(Integer.toString(out.number()), DialogPalette.DANGER).appendNewline()
                    .append(Component.text("Voted out", DialogPalette.DANGER)));
            badges.pop(out.id(), 2.1f);
            Msg.title(everyone, Component.text(out.name(), DialogPalette.DANGER),
                    Msg.text("was voted out with " + votes + (votes == 1 ? " vote" : " votes"), DialogPalette.MUTED), 5, 80, 20);
            Msg.broadcast(Msg.text(out.name(), DialogPalette.DANGER).append(Msg.text(" was voted out with " + votes + (votes == 1 ? " vote." : " votes."), DialogPalette.TEXT)));
            Sounds.ELIMINATED.playAll();
            // Applied in finish(), once the reveal no longer holds the player, so their game mode changes too.
            if (eliminate) votedOut = out.id();
        }
        plugin.getLogger().info("Vote results: " + String.join(", ", session.results().stream()
                .map(tally -> tally.candidate().name() + " " + tally.votes()).toList()));
        tasks.later(140, this::finish);
    }

    private void finish() {
        if (finished) return;
        finished = true;
        tasks.close();
        HandlerList.unregisterAll(this);
        for (Display display : displays) {
            if (!display.isValid()) continue;
            Holograms.animate(display, Holograms.scaled(0.01f), 6);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> Holograms.remove(display), 8L);
        }
        displays.clear();
        for (UUID id : gathered) plugin.getRegistry().release(id, this);
        gathered.clear();
        service.revealFinished(this);
        if (votedOut != null) {
            try { plugin.getRoleManager().setRole(votedOut, PlayerRole.SPECTATOR); }
            catch (RuntimeException failure) { plugin.getLogger().warning("Could not make the voted-out player a spectator: " + failure.getMessage()); }
        }
    }

    @Override public void forceStop(String reason) {
        finish();
    }

    @Override public void handleQuit(Player player) {
        plugin.getRegistry().release(player.getUniqueId(), this);
        gathered.remove(player.getUniqueId());
    }

    /** Candidates stay on their spot during the reveal but can look around. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!gathered.contains(event.getPlayer().getUniqueId()) || !event.hasChangedPosition()) return;
        if (event instanceof PlayerTeleportEvent) { if (!moving) event.setCancelled(true); return; }
        Location held = event.getFrom().clone();
        held.setYaw(event.getTo().getYaw());
        held.setPitch(event.getTo().getPitch());
        event.setTo(held);
    }

    private static List<Player> onlinePlayers() { return List.copyOf(Bukkit.getOnlinePlayers()); }
    private static Collection<UUID> onlineAll() { return Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList(); }
}
