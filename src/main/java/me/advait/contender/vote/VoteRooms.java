package me.advait.contender.vote;

import me.advait.contender.Contender;
import me.advait.contender.role.PlayerRole;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Where players wait during a vote: contestants in the voting room, everyone else (spectators, camera crew,
 * directors) in the judge room. Players busy in a match, interview or spectating stay where they are.
 * Each room is saved with the facing it was set with, and players arrive facing that way.
 */
final class VoteRooms {
    /** Players further than this from the room they were sent to have been moved since, so they aren't sent back. */
    private static final double RETURN_RANGE = 64;
    private final Contender plugin;
    private final Map<UUID, Location> returns = new HashMap<>();
    private final Map<UUID, Location> sentTo = new HashMap<>();

    VoteRooms(Contender plugin) { this.plugin = plugin; }

    void set(VoteService.Room room, Location location) {
        if (plugin.getArenas().isArenaWorld(location.getWorld())) throw new IllegalArgumentException("Set the " + room.label + " outside the arena world.");
        var config = plugin.getConfig();
        String path = "votes.rooms." + room.key;
        config.set(path + ".world", location.getWorld().getName());
        config.set(path + ".x", location.getX());
        config.set(path + ".y", location.getY());
        config.set(path + ".z", location.getZ());
        config.set(path + ".yaw", (double) location.getYaw());
        config.set(path + ".pitch", (double) location.getPitch());
        plugin.saveConfig();
    }

    void clear(VoteService.Room room) {
        plugin.getConfig().set("votes.rooms." + room.key, null);
        plugin.saveConfig();
    }

    Location location(VoteService.Room room) {
        var config = plugin.getConfig();
        String path = "votes.rooms." + room.key;
        String name = config.getString(path + ".world");
        World world = name == null ? null : Bukkit.getWorld(name);
        if (world == null) return null;
        return new Location(world, config.getDouble(path + ".x"), config.getDouble(path + ".y"), config.getDouble(path + ".z"),
                (float) config.getDouble(path + ".yaw"), (float) config.getDouble(path + ".pitch"));
    }

    boolean anySet() { return location(VoteService.Room.VOTING) != null || location(VoteService.Room.JUDGE) != null; }

    /** Moves every free player to their room. With {@link VoteService.RoomMode#RETURN}, remembers where they were. */
    void gather(VoteService.RoomMode mode) {
        returns.clear();
        sentTo.clear();
        if (mode == VoteService.RoomMode.OFF) return;
        Map<VoteService.Room, List<Player>> groups = new EnumMap<>(VoteService.Room.class);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead() || !plugin.getRegistry().isFree(player.getUniqueId())) continue;
            groups.computeIfAbsent(roomFor(player), ignored -> new ArrayList<>()).add(player);
        }
        groups.forEach((room, players) -> {
            Location center = location(room);
            if (center == null) return;
            players.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
            List<Location> spots = spots(center, players.size());
            for (int i = 0; i < players.size(); i++) move(players.get(i), spots.get(i), mode);
        });
    }

    /** A player who joins during a vote goes to their room too. */
    void admit(Player player, VoteService.RoomMode mode) {
        if (mode == VoteService.RoomMode.OFF || player.isDead() || !plugin.getRegistry().isFree(player.getUniqueId())) return;
        Location center = location(roomFor(player));
        if (center != null) move(player, spots(center, 1).getFirst(), mode);
    }

    private VoteService.Room roomFor(Player player) {
        return plugin.getRoleManager().getRole(player.getUniqueId()) == PlayerRole.CONTESTANT ? VoteService.Room.VOTING : VoteService.Room.JUDGE;
    }

    private void move(Player player, Location spot, VoteService.RoomMode mode) {
        Location from = player.getLocation();
        if (!player.teleport(spot)) return;
        if (mode == VoteService.RoomMode.RETURN) {
            returns.put(player.getUniqueId(), from);
            sentTo.put(player.getUniqueId(), spot);
        }
    }

    /**
     * Sends players back where they were, unless they have since joined a game or been moved somewhere else. Being
     * near the vote stage counts as still being part of the vote, since the reveal moves candidates there.
     */
    void sendBack(Location stage) {
        Map<UUID, Location> back = new HashMap<>(returns);
        returns.clear();
        back.forEach((id, location) -> {
            Player player = Bukkit.getPlayer(id);
            Location room = sentTo.get(id);
            if (player == null || player.isDead() || !plugin.getRegistry().isFree(id) || room == null || location.getWorld() == null) return;
            if (!near(player, room) && (stage == null || !near(player, stage))) return;
            player.teleport(location);
        });
        sentTo.clear();
    }

    private static boolean near(Player player, Location place) {
        return player.getWorld().equals(place.getWorld()) && player.getLocation().distanceSquared(place) <= RETURN_RANGE * RETURN_RANGE;
    }

    void forget(UUID player) {
        returns.remove(player);
        sentTo.remove(player);
    }

    /**
     * Standing spots around the room's center, closest first, all facing the room's saved direction. Only spots
     * with room for a player and ground underneath are used, so nobody lands in a wall; if there aren't enough,
     * the rest share the center.
     */
    static List<Location> spots(Location center, int count) {
        List<Location> spots = new ArrayList<>();
        Location middle = standable(center);
        spots.add(middle != null ? middle : center.clone());
        for (int ring = 1; spots.size() < count && ring <= 6; ring++) {
            int points = 6 * ring;
            double radius = ring * 1.3;
            for (int i = 0; i < points && spots.size() < count; i++) {
                double angle = Math.PI * 2 * i / points;
                Location spot = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
                Location standing = standable(spot);
                if (standing != null) spots.add(standing);
            }
        }
        while (spots.size() < count) spots.add(center.clone());
        return spots;
    }

    /**
     * The spot moved onto the floor: up a block for a step or a slightly sunken center, or down up to two blocks.
     * Null when there's no room for a player with solid ground underneath.
     */
    static Location standable(Location spot) {
        for (int lift = 1; lift >= -2; lift--) {
            Location feet = spot.clone().add(0, lift, 0);
            Block block = feet.getBlock();
            if (!block.isPassable() || block.isLiquid() || !block.getRelative(0, 1, 0).isPassable()) continue;
            if (block.getRelative(0, -1, 0).getType().isSolid()) {
                feet.setY(block.getY());
                return feet;
            }
        }
        return null;
    }
}
