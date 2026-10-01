package dev.fakeplayers;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
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

/** A server-side fake player. The server simulates its physics every tick. */
public class Bot extends ServerPlayer {

    private final FakePlayersPlugin plugin;
    boolean fight;
    int walkTicks;
    private int attackCooldown;
    private int deadTicks;

    private Bot(FakePlayersPlugin plugin, ServerLevel level, GameProfile profile) {
        super(MinecraftServer.getServer(), level, profile, ClientInformation.createDefault());
        this.plugin = plugin;
    }

    static Bot spawn(FakePlayersPlugin plugin, String name, Location loc) {
        MinecraftServer server = MinecraftServer.getServer();
        ServerLevel level = ((CraftWorld) loc.getWorld()).getHandle();
        GameProfile profile = UUIDUtil.createOfflineProfile(name);
        Bot bot = new Bot(plugin, level, profile);
        bot.snapTo(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), 0);
        FakeConnection connection = new FakeConnection();
        server.getPlayerList().placeNewPlayer(connection, bot, CommonListenerCookie.createInitial(profile, false));
        if (!connection.isConnected() || bot.hasDisconnected()) return null;
        // A real client reports when it has loaded the world; until then the server ignores its attacks.
        bot.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        return bot;
    }

    void remove(String reason) {
        this.connection.disconnect(Component.literal(reason));
    }

    @Override
    public void tick() {
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
        } else {
            this.zza = 0;
            this.setJumping(false);
        }
        super.tick();
        this.doTick();
    }

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

    /** Attack exactly the way a client's attack packet does. */
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
