package me.advait.contender.vote;

import me.advait.contender.Contender;
import me.advait.contender.role.PlayerRole;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * While a vote is open, contestants in the voting room get Slowness II and can't jump, so they stay put for the
 * cameras. It lifts as soon as they leave the room or voting closes. The room is everything within
 * {@code votes.voting-room-radius} blocks (10 by default) of where /setvotingroom was used.
 */
final class VoteRoomHold {
    private static final NamespacedKey NO_JUMP = new NamespacedKey("contender", "vote_room_no_jump");
    private static final double HEIGHT = 5;
    private final Contender plugin;
    private final VoteRooms rooms;
    private final Set<UUID> held = new HashSet<>();

    VoteRoomHold(Contender plugin, VoteRooms rooms) {
        this.plugin = plugin;
        this.rooms = rooms;
    }

    /** Holds everyone in the voting room while {@code open}, and lets go of everyone else. */
    void update(boolean open) {
        Location room = open ? rooms.location(VoteService.Room.VOTING) : null;
        double radius = Math.clamp(plugin.getConfig().getDouble("votes.voting-room-radius", 10), 1, 64);
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            boolean hold = room != null && inside(player, room, radius) && player.getGameMode() != GameMode.SPECTATOR
                    && plugin.getRoleManager().getRole(id) == PlayerRole.CONTESTANT && plugin.getRegistry().isFree(id);
            if (hold) apply(player);
            else if (held.remove(id)) strip(player);
        }
        held.removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    /** Whether this player is being held in the voting room right now. */
    boolean holds(UUID player) { return held.contains(player); }

    void releaseAll() {
        for (UUID id : held) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) strip(player);
        }
        held.clear();
    }

    void release(Player player) {
        held.remove(player.getUniqueId());
        strip(player);
    }

    /** Takes the hold off. Also run on join, in case the server stopped while someone was held. */
    static void strip(Player player) {
        PotionEffect slowness = player.getPotionEffect(PotionEffectType.SLOWNESS);
        if (slowness != null && slowness.getAmplifier() == 1 && slowness.isInfinite()) player.removePotionEffect(PotionEffectType.SLOWNESS);
        var jump = player.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null) jump.removeModifier(NO_JUMP);
    }

    private void apply(Player player) {
        held.add(player.getUniqueId());
        if (!player.hasPotionEffect(PotionEffectType.SLOWNESS)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, PotionEffect.INFINITE_DURATION, 1, true, false, true));
        }
        var jump = player.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null && jump.getModifier(NO_JUMP) == null) {
            jump.addModifier(new AttributeModifier(NO_JUMP, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
    }

    private static boolean inside(Player player, Location room, double radius) {
        Location at = player.getLocation();
        if (!at.getWorld().equals(room.getWorld()) || Math.abs(at.getY() - room.getY()) > HEIGHT) return false;
        double dx = at.getX() - room.getX(), dz = at.getZ() - room.getZ();
        return dx * dx + dz * dz <= radius * radius;
    }
}
