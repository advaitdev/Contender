package dev.fakeplayers;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A server-side fake player. It has no client, so the server simulates its physics (gravity, knockback,
 * walking) every tick, and the bot sends the replies a vanilla client would: confirming teleports, saying
 * the world has loaded, and pressing Respawn.
 */
public class Bot extends ServerPlayer {

    private final FakePlayersPlugin plugin;
    /** Where the bot wanders around. Moves with the bot whenever it is teleported. */
    Location home;
    int radius;
    boolean fight;
    boolean wander;
    int walkTicks;
    private int wanderTimer;
    private boolean walking;
    private int attackCooldown;
    private int deadTicks;
    /** Replies a real client would send, queued by the connection (any thread) and run on the next tick. */
    private final Queue<Runnable> clientReplies = new ConcurrentLinkedQueue<>();

    private Bot(FakePlayersPlugin plugin, ServerLevel level, GameProfile profile, Location home, int radius) {
        super(MinecraftServer.getServer(), level, profile, ClientInformation.createDefault());
        this.plugin = plugin;
        this.home = home;
        this.radius = radius;
    }

    static Bot spawn(FakePlayersPlugin plugin, String name, Location loc, int radius) {
        MinecraftServer server = MinecraftServer.getServer();
        ServerLevel level = ((CraftWorld) loc.getWorld()).getHandle();
        GameProfile profile = UUIDUtil.createOfflineProfile(name);
        Bot bot = new Bot(plugin, level, profile, loc.clone(), radius);
        bot.snapTo(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), 0);
        FakeConnection connection = new FakeConnection();
        server.getPlayerList().placeNewPlayer(connection, bot, CommonListenerCookie.createInitial(profile, false));
        if (!connection.isConnected() || bot.hasDisconnected()) return null;
        // A real client reports when it has loaded the world; until then the server ignores its attacks.
        bot.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        return bot;
    }

    /** Wander around a new spot from now on. */
    void rehome(Location to) {
        this.home = to.clone();
    }

    /** Set once the bot is told to leave; it stays online until the next tick. */
    volatile boolean removed;

    void remove(String reason) {
        this.removed = true;
        this.connection.disconnect(Component.literal(reason));
    }

    /** What a vanilla client does on its own when it receives these packets. Any thread. */
    void onPacket(Packet<?> packet) {
        switch (packet) {
            // Confirm teleports, or the server sends the bot back to the destination every second and ignores its
            // moves. Wandering continues around the new spot.
            case ClientboundPlayerPositionPacket teleport -> clientReplies.add(() -> {
                this.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(teleport.id()));
                rehome(this.getBukkitEntity().getLocation());
            });
            case ClientboundSystemChatPacket chat -> ChatCapture.record(this, chat.overlay() ? "actionbar" : "system", chat.content());
            case ClientboundPlayerChatPacket chat -> ChatCapture.record(this, "chat", chat.chatType().decorate(
                    chat.unsignedContent() != null ? chat.unsignedContent() : Component.literal(chat.body().content())));
            case ClientboundDisguisedChatPacket chat -> ChatCapture.record(this, "chat", chat.chatType().decorate(chat.message()));
            case ClientboundSetTitleTextPacket title -> ChatCapture.record(this, "title", title.text());
            case ClientboundSetSubtitleTextPacket subtitle -> ChatCapture.record(this, "subtitle", subtitle.text());
            case ClientboundSetActionBarTextPacket bar -> ChatCapture.record(this, "actionbar", bar.text());
            default -> { }
        }
    }

    @Override
    public void tick() {
        for (Runnable reply; (reply = clientReplies.poll()) != null; ) {
            if (!this.hasDisconnected()) reply.run();
        }
        if (this.tickCount % 10 == 0) {
            this.connection.resetPosition();
            this.level().getChunkSource().move(this);
        }
        // Dimension changes and respawns wait for the client to report that it loaded the world.
        if (!this.connection.hasClientLoaded()) this.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        // A real client's first movement packet after a dimension change ends its transfer invulnerability.
        if (this.isChangingDimension()) this.hasChangedDimension();
        if (!this.isAlive()) {
            // Real clients send a respawn request once the death screen opens.
            if (++this.deadTicks == 3) this.connection.handleClientCommand(
                    new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        } else {
            this.deadTicks = 0;
        }
        if (this.isAlive() && (this.fight || this.plugin.fightAll) && !this.isSpectator() && fightTick()) {
            // chasing someone
        } else if (this.walkTicks > 0) {
            this.walkTicks--;
            this.zza = 1f;
            this.setJumping(this.horizontalCollision);
        } else if (this.isAlive() && (this.wander || this.plugin.wanderAll) && !this.isSpectator()) {
            wanderTick();
        } else {
            this.zza = 0;
            this.setJumping(false);
        }
        super.tick();
        // What the packet listener does for real players when they send movement. A real client sends none
        // while it waits for the chunks around it, so moving earlier would load them on the main thread.
        if (terrainLoaded()) this.doTick();
    }

    /** Whether the chunks a move this tick could touch (the 3x3 around the bot) are loaded. */
    private boolean terrainLoaded() {
        int cx = this.getBlockX() >> 4, cz = this.getBlockZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!this.level().hasChunk(cx + dx, cz + dz)) return false;
            }
        }
        return true;
    }

    /** Chase and punch the nearest player. Returns false if nobody is near. */
    private boolean fightTick() {
        Player target = this.level().getNearestPlayer(this.getX(), this.getY(), this.getZ(), 24.0,
                e -> e != this && e.isAlive() && !e.isSpectator() && !((Player) e).isCreative() && !e.isInvisible());
        if (target == null) return false;
        double dx = target.getX() - this.getX(), dz = target.getZ() - this.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        this.setYRot(yaw);
        this.setYHeadRot(yaw);
        double distanceSqr = this.distanceToSqr(target);
        this.zza = distanceSqr > 2.0 * 2.0 ? 1f : 0f;
        this.setJumping(this.zza > 0 && (this.horizontalCollision || this.isInWater()));
        if (--this.attackCooldown <= 0 && distanceSqr < 3.0 * 3.0) {
            this.attackCooldown = 10 + this.random.nextInt(5);
            punch(target);
        }
        return true;
    }

    /** Walk in a random direction for a few seconds, pause now and then, and head back when too far from home. */
    private void wanderTick() {
        if (--this.wanderTimer <= 0) {
            this.wanderTimer = 20 + this.random.nextInt(60);
            this.walking = this.random.nextInt(4) != 0;
            float yaw = this.random.nextFloat() * 360 - 180;
            double dx = this.home.getX() - this.getX(), dz = this.home.getZ() - this.getZ();
            if (dx * dx + dz * dz > (double) this.radius * this.radius) {
                yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)); // head back home
            }
            this.setYRot(yaw);
            this.setYHeadRot(yaw);
        }
        this.zza = this.walking ? 1f : 0f;
        this.setJumping(this.walking && (this.horizontalCollision || this.isInWater()));
    }

    /** Attack exactly the way a client's attack packet does: attack, then swing. */
    void punch(net.minecraft.world.entity.Entity target) {
        this.connection.handleAttack(new ServerboundAttackPacket(target.getId()));
        this.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
    }

    void useItem() {
        this.connection.handleUseItem(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 0, this.getYRot(), this.getXRot()));
    }

    void swing() {
        this.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
    }
}
