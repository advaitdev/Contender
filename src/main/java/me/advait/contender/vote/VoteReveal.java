package me.advait.contender.vote;

import io.papermc.paper.entity.LookAnchor;
import me.advait.contender.Contender;
import me.advait.contender.activity.Activity;
import me.advait.contender.activity.ActivityRegistry;
import me.advait.contender.core.Msg;
import me.advait.contender.core.Sounds;
import me.advait.contender.core.Tasks;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.display.Holograms;
import me.advait.contender.display.Theme;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * The results ceremony. Candidates walk into a straight line on the vote stage (along whichever of X or Z has
 * more room), then a spotlight sweeps back and forth along the line, slows down and stops on whoever was voted
 * out. Without a saved stage the results are announced wherever everyone is standing.
 */
final class VoteReveal implements Activity, Listener {
    /** Gap between neighbors in the line; squeezed down to the minimum when the stage is short. */
    private static final double SPACING = 2.0, MIN_SPACING = 0.8;
    /** Gap between rows when everyone doesn't fit in one line. */
    private static final double ROW_GAP = 1.4;
    /** How far along each direction the stage floor is checked. */
    private static final int MAX_REACH = 24;
    /** Players further away than this (or in another world) are teleported instead of walked into place. */
    private static final double WALK_RANGE = 40;
    /** Anyone still not in place after this many ticks is put there directly. */
    private static final int WALK_TIMEOUT = 100;
    private final Contender plugin;
    private final VoteService service;
    private final VoteSession session;
    private final VoteService.Elimination elimination;
    private final Tasks tasks;
    /** Candidates on the stage, in order along the line. */
    private final List<UUID> line = new ArrayList<>();
    private final Map<UUID, Location> spots = new HashMap<>();
    private final Set<UUID> placed = new HashSet<>();
    private final List<Display> displays = new ArrayList<>();
    private Vector facing = new Vector(0, 0, 1);
    private BukkitTask walking;
    private int walkTicks;
    private boolean moving;
    private UUID votedOut;
    private boolean finished;
    /** Whether to show who voted for whom once the spotlight lands. The ceremony waits for those displays to go. */
    private final boolean receipts;

    record Layout(List<Location> spots, Vector facing) { }

    VoteReveal(Contender plugin, VoteService service, VoteSession session, VoteService.Elimination elimination, boolean receipts) {
        this.plugin = plugin;
        this.receipts = receipts;
        this.service = service;
        this.session = session;
        this.elimination = elimination;
        this.tasks = new Tasks(plugin, "Vote reveal");
    }

    @Override public String displayName() { return "the vote reveal"; }

    void play() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        List<VoteSession.Candidate> leaders = session.leaders();
        int top = leaders.isEmpty() ? 0 : session.votesFor(leaders.getFirst().id());
        Sounds.REVEAL.playAll();
        Location stage = service.stageLocation();
        if (stage != null) lineUp(stage);
        if (line.size() < 2 || leaders.isEmpty()) {
            // Give the timers a moment to turn into the results first.
            tasks.later(50, () -> conclude(leaders, top));
            return;
        }
        walk(() -> tasks.later(15, () -> spotlight(leaders, top)));
    }

    // ---- The line ------------------------------------------------------------------------------

    private void lineUp(Location stage) {
        List<VoteSession.Candidate> present = new ArrayList<>();
        for (VoteSession.Tally tally : session.results()) {
            Player player = Bukkit.getPlayer(tally.candidate().id());
            if (player == null || player.isDead() || !plugin.getRegistry().isFree(player.getUniqueId())) continue;
            present.add(tally.candidate());
        }
        if (present.isEmpty()) return;
        present.sort(Comparator.comparingInt(VoteSession.Candidate::number));
        Layout layout = layout(stage, present.size(), preferredFacing(stage));
        facing = layout.facing();
        for (int i = 0; i < present.size(); i++) {
            Player player = Bukkit.getPlayer(present.get(i).id());
            try {
                plugin.getRegistry().claim(player.getUniqueId(), this, ActivityRegistry.Involvement.PLAYING);
            } catch (IllegalStateException busy) { continue; }
            Location spot = layout.spots().get(i);
            line.add(player.getUniqueId());
            spots.put(player.getUniqueId(), spot);
            boolean far = !player.getWorld().equals(spot.getWorld()) || player.getLocation().distanceSquared(spot) > WALK_RANGE * WALK_RANGE;
            if (far) {
                moving = true;
                try { player.teleport(spot); } finally { moving = false; }
                placed.add(player.getUniqueId());
            }
        }
    }

    /** The way the director faced when setting the stage, or toward the judges, so the line faces the audience. */
    private Vector preferredFacing(Location stage) {
        if (service.stageHasFacing()) return stage.getDirection().setY(0);
        Location judges = service.roomLocation(VoteService.Room.JUDGE);
        if (judges != null && judges.getWorld().equals(stage.getWorld())) {
            Vector toward = judges.toVector().subtract(stage.toVector()).setY(0);
            if (toward.lengthSquared() > 1e-6) return toward;
        }
        return new Vector(0, 0, 1);
    }

    /**
     * Spots in a straight line through the stage center, along X or Z, whichever has more open floor. The line is
     * kept on the floor (shifted if the center is near one end), and everyone faces across it, toward the side
     * closest to {@code preferred}.
     */
    static Layout layout(Location stage, int count, Vector preferred) {
        Location found = VoteRooms.standable(stage);
        Location center = found != null ? found : stage.clone();
        int plusX = reach(center, new Vector(1, 0, 0)), minusX = reach(center, new Vector(-1, 0, 0));
        int plusZ = reach(center, new Vector(0, 0, 1)), minusZ = reach(center, new Vector(0, 0, -1));
        int alongX = plusX + minusX, alongZ = plusZ + minusZ;
        // On a square stage, run the line across the way the director faced.
        boolean onX = alongX != alongZ ? alongX > alongZ : Math.abs(preferred.getZ()) >= Math.abs(preferred.getX());
        Vector axis = onX ? new Vector(1, 0, 0) : new Vector(0, 0, 1);
        int plus = onX ? plusX : plusZ, minus = onX ? minusX : minusZ;
        Vector side = onX ? new Vector(0, 0, 1) : new Vector(1, 0, 0);
        if (side.dot(preferred) < 0) side.multiply(-1);
        // Never run past the ends of the stage: if everyone doesn't fit in one line, add rows behind it.
        int room = plus + minus;
        int perRow = count <= 1 ? 1 : Math.clamp((int) Math.floor(room / MIN_SPACING) + 1, 1, count);
        int rows = (count + perRow - 1) / perRow;
        List<Location> spots = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            int inRow = Math.min(perRow, count - row * perRow);
            double spacing = inRow <= 1 ? 0 : Math.min(SPACING, room / (double) (inRow - 1));
            double total = spacing * (inRow - 1);
            double start = Math.clamp(-total / 2, -minus, Math.max(-minus, plus - total));
            Vector back = side.clone().multiply(-ROW_GAP * row);
            for (int i = 0; i < inRow; i++) {
                Location wanted = center.clone().add(axis.clone().multiply(start + i * spacing)).add(back);
                Location spot = onStage(wanted, center, spots, axis, side);
                spot.setDirection(side);
                spot.setPitch(0);
                spots.add(spot);
            }
        }
        return new Layout(spots, side);
    }

    /**
     * The nearest floor to {@code wanted} at the stage's own height that isn't on top of someone else's spot, so
     * nobody ends up on a wall, a step or off the edge. Falls back to the stage center.
     */
    private static Location onStage(Location wanted, Location center, List<Location> taken, Vector axis, Vector side) {
        for (int ring = 0; ring <= 6; ring++) {
            for (int a = -ring; a <= ring; a++) for (int b = -ring; b <= ring; b++) {
                if (Math.max(Math.abs(a), Math.abs(b)) != ring) continue;
                Location probe = wanted.clone().add(axis.clone().multiply(a * 0.5)).add(side.clone().multiply(b * 0.5));
                Location floor = VoteRooms.standable(probe);
                if (floor == null || Math.abs(floor.getY() - center.getY()) > 0.6) continue;
                if (taken.stream().anyMatch(other -> other.getWorld().equals(floor.getWorld()) && other.distanceSquared(floor) < 0.7 * 0.7)) continue;
                return floor;
            }
        }
        return center.clone();
    }

    /**
     * Open floor, in whole blocks, from the center along a direction. The stage ends at a wall, a gap, or a change
     * in floor height, so a raised platform counts only its own top.
     */
    private static int reach(Location center, Vector step) {
        int reach = 0;
        for (int distance = 1; distance <= MAX_REACH; distance++) {
            Location floor = VoteRooms.standable(center.clone().add(step.clone().multiply(distance)));
            if (floor == null || Math.abs(floor.getY() - center.getY()) > 0.6) break;
            reach = distance;
        }
        return reach;
    }

    /** Pushes everyone toward their spot each tick until they're all in place, then runs {@code then}. */
    private void walk(Runnable then) {
        walkTicks = 0;
        walking = tasks.repeat(1, 1, () -> {
            walkTicks++;
            boolean done = true;
            for (UUID id : line) {
                if (placed.contains(id)) continue;
                Player player = Bukkit.getPlayer(id);
                Location target = spots.get(id);
                if (player == null || target == null) { placed.add(id); continue; }
                Vector to = target.toVector().subtract(player.getLocation().toVector());
                double rise = to.getY();
                to.setY(0);
                double distance = to.length();
                if (distance < 0.3 || walkTicks > WALK_TIMEOUT) {
                    // Snap onto the spot, height included: they're held still from here, so someone caught mid-hop
                    // would otherwise hang in the air.
                    if (distance >= 0.3 || Math.abs(rise) > 0.1) {
                        Location snap = target.clone();
                        snap.setYaw(player.getLocation().getYaw());
                        snap.setPitch(player.getLocation().getPitch());
                        moving = true;
                        try { player.teleport(snap); } finally { moving = false; }
                    }
                    player.setVelocity(new Vector(0, Math.min(0, player.getVelocity().getY()), 0));
                    face(player, target);
                    placed.add(id);
                    continue;
                }
                done = false;
                // Fast across the floor, easing off near the spot; a hop for a step up.
                Vector velocity = to.normalize().multiply(Math.clamp(distance * 0.3, 0.1, 0.45));
                @SuppressWarnings("deprecation") boolean grounded = player.isOnGround();
                velocity.setY(rise > 0.6 && grounded ? 0.42 : player.getVelocity().getY());
                player.setVelocity(velocity);
            }
            if (done) {
                tasks.cancel(walking);
                walking = null;
                then.run();
            }
        });
    }

    private void face(Player player, Location spot) {
        Location eye = spot.clone().add(0, player.getEyeHeight(), 0);
        player.lookAt(eye.getX() + facing.getX() * 10, eye.getY(), eye.getZ() + facing.getZ() * 10, LookAnchor.EYES);
    }

    // ---- The spotlight -------------------------------------------------------------------------

    /**
     * A beam sweeps back and forth along the line, slowing down, and stops on the voted-out player. On a tie, or
     * when Skip won, it slows down over one of the top players as if it's about to land, hangs there, then pulls
     * back into the sky without picking anyone.
     */
    private void spotlight(List<VoteSession.Candidate> leaders, int top) {
        List<UUID> standing = new ArrayList<>();
        for (UUID id : line) if (Bukkit.getPlayer(id) != null) standing.add(id);
        boolean tie = leaders.size() > 1 || session.skipped();
        UUID stop = !tie && standing.contains(leaders.getFirst().id()) ? leaders.getFirst().id() : null;
        if (tie) {
            List<UUID> tied = leaders.stream().map(VoteSession.Candidate::id).filter(standing::contains).toList();
            List<UUID> pool = tied.isEmpty() ? standing : tied;
            stop = pool.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size()));
        }
        // Nobody here to land on (the voted-out player left): skip straight to the result.
        if (standing.size() < 2 || stop == null) { conclude(leaders, top); return; }
        List<Integer> steps = sweep(standing.size(), standing.indexOf(stop));
        Theme theme = plugin.getThemes().current();
        BlockDisplay beam = Holograms.block(beamAt(standing.getFirst()), Material.WHITE_STAINED_GLASS.createBlockData(), Holograms.box(0.9f, 14f, 0.9f, 0), "vote_reveal");
        beam.setGlowing(true);
        beam.setGlowColorOverride(Color.fromRGB(theme.primary().value()));
        beam.setBrightness(new Display.Brightness(15, 15));
        beam.setTeleportDuration(3);
        displays.add(beam);
        long time = 0;
        for (int step = 0; step < steps.size(); step++) {
            UUID at = standing.get(steps.get(step));
            double progress = (double) step / steps.size();
            time += Math.round(3 + 14 * progress * progress);
            boolean last = step == steps.size() - 1;
            tasks.later(time, () -> {
                if (beam.isValid()) beam.teleport(beamAt(at));
                for (Player viewer : onlinePlayers()) Sounds.TICK.play(viewer, last ? 2f : 1.2f);
                if (!last) return;
                if (tie) tasks.later(30, () -> pullAway(beam, leaders, top));
                else tasks.later(16, () -> conclude(leaders, top));
            });
        }
    }

    /** The fake-out: the beam shoots back up into the sky and thins out, then the tie or the skip is announced. */
    private void pullAway(BlockDisplay beam, List<VoteSession.Candidate> leaders, int top) {
        Sounds.WHOOSH.playAll();
        Sounds.FIZZLE.playAll();
        if (beam.isValid()) {
            Holograms.animate(beam, new org.bukkit.util.Transformation(new org.joml.Vector3f(-0.45f, 14f, -0.45f), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(0.9f, 0.01f, 0.9f), new org.joml.Quaternionf()), 12);
        }
        tasks.later(20, () -> {
            Holograms.remove(beam);
            displays.remove(beam);
            conclude(leaders, top);
        });
    }

    /**
     * Positions the beam visits: left to right, back again, and on until it has turned around at least twice and
     * reaches the target. Index 0 is one end of the line.
     */
    static List<Integer> sweep(int count, int target) {
        List<Integer> steps = new ArrayList<>(List.of(0));
        int position = 0, direction = 1, turns = 0;
        while (steps.size() < 200) {
            if (position + direction < 0 || position + direction >= count) { direction = -direction; turns++; }
            position += direction;
            steps.add(position);
            if (turns >= 2 && position == target) break;
        }
        return steps;
    }

    private Location beamAt(UUID player) {
        Location spot = spots.get(player);
        if (spot == null) { Player online = Bukkit.getPlayer(player); spot = online == null ? null : online.getLocation(); }
        // The beam comes down from the sky and stops just above their head.
        return spot == null ? null : spot.clone().add(0, 2.3, 0);
    }

    // ---- Results -------------------------------------------------------------------------------

    private void conclude(List<VoteSession.Candidate> leaders, int votes) {
        if (finished) return;
        // The timers turn into the results now that the spotlight is done, and votes are shown if they aren't anonymous.
        service.showResults(session);
        long notBefore = receipts ? service.showReceipts(session) : 0;
        if (session.skipped()) {
            int skips = session.skips();
            Msg.broadcast(Msg.text("Skip got the most votes (" + skips + "), so nobody was voted out.", DialogPalette.ACCENT));
            Sounds.ANNOUNCE.playAll();
        } else if (leaders.isEmpty()) {
            Msg.broadcast(Msg.text(session.skips() > 0 ? "Only skips were cast, so nobody was voted out." : "The vote ended with no votes cast.", DialogPalette.MUTED));
        } else if (leaders.size() > 1) {
            String names = String.join(" & ", leaders.stream().map(VoteSession.Candidate::name).toList());
            Msg.broadcast(Msg.text("The vote is tied between " + names + " with " + votes + (votes == 1 ? " vote" : " votes") + " each.", DialogPalette.ACCENT));
            Sounds.ANNOUNCE.playAll();
        } else {
            VoteSession.Candidate out = leaders.getFirst();
            Player player = Bukkit.getPlayer(out.id());
            if (player != null) {
                player.getWorld().strikeLightningEffect(player.getLocation());
                plugin.getCelebrations().fireworks(player.getLocation(), 4);
            }
            Msg.broadcast(Msg.text(out.name(), DialogPalette.DANGER).append(Msg.text(" was voted out with " + votes + (votes == 1 ? " vote." : " votes."), DialogPalette.TEXT)));
            Sounds.ELIMINATED.playAll();
            if (elimination == VoteService.Elimination.KILL) {
                // Let go of them first, so they can die, respawn on the spot and turn into a spectator.
                release(out.id());
                service.voteOut(out.id(), elimination);
            } else if (elimination == VoteService.Elimination.SPECTATE) {
                // Applied in finish(), once the reveal no longer holds the player, so their game mode changes too.
                votedOut = out.id();
            }
        }
        plugin.getLogger().info("Vote results: " + String.join(", ", session.results().stream()
                .map(tally -> tally.candidate().name() + " " + tally.votes()).toList()) + ", Skip " + session.skips());
        tasks.later(Math.max(140, (notBefore - System.currentTimeMillis()) / 50 + 5), this::finish);
    }

    private void release(UUID id) {
        line.remove(id);
        spots.remove(id);
        placed.remove(id);
        plugin.getRegistry().release(id, this);
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
        for (UUID id : List.copyOf(line)) plugin.getRegistry().release(id, this);
        line.clear();
        service.revealFinished(this);
        if (votedOut != null) service.voteOut(votedOut, VoteService.Elimination.SPECTATE);
    }

    @Override public void forceStop(String reason) { finish(); }

    @Override public void handleQuit(Player player) { release(player.getUniqueId()); }

    /** Candidates walk into the line, then stay on their spot but can look around. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!line.contains(id) || !event.hasChangedPosition()) return;
        if (event instanceof PlayerTeleportEvent) { if (!moving) event.setCancelled(true); return; }
        if (!placed.contains(id)) return; // still walking into place
        Location held = event.getFrom().clone();
        held.setYaw(event.getTo().getYaw());
        held.setPitch(event.getTo().getPitch());
        event.setTo(held);
    }

    private static List<Player> onlinePlayers() { return List.copyOf(Bukkit.getOnlinePlayers()); }
}
