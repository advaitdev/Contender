package me.advait.contender.spectator;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/** Paper 26.2 packet transport. Adapted from UHCR's virtual spectator avatars.
 * The Bukkit allay is created without spawning it: it has no server hitbox or AI.
 * Reflection keeps server implementation types out of the plugin's public classes. */
public final class PaperAllayAvatar implements AllayAvatar {
    private static Class<?> nms(String name) throws ClassNotFoundException { return Class.forName("net.minecraft." + name); }
    public static final class Transport {
        private final Constructor<?> vector, position, spawn, teleport, metadata, remove;
        private final Method handle, playerHandle, type, data, values, send;
        private final Field connection;
        public Transport() {
            try {
                String craft = org.bukkit.Bukkit.getServer().getClass().getPackageName();
                Class<?> vec = nms("world.phys.Vec3"), pos = nms("world.entity.PositionMoveRotation");
                vector = vec.getConstructor(double.class, double.class, double.class);
                position = pos.getConstructor(vec, vec, float.class, float.class);
                spawn = nms("network.protocol.game.ClientboundAddEntityPacket").getConstructor(int.class, java.util.UUID.class,
                        double.class, double.class, double.class, float.class, float.class,
                        nms("world.entity.EntityType"), int.class, vec, double.class);
                teleport = nms("network.protocol.game.ClientboundTeleportEntityPacket").getConstructor(int.class, pos, Set.class, boolean.class);
                metadata = nms("network.protocol.game.ClientboundSetEntityDataPacket").getConstructor(int.class, List.class);
                remove = nms("network.protocol.game.ClientboundRemoveEntitiesPacket").getConstructor(int[].class);
                handle = Class.forName(craft + ".entity.CraftEntity").getMethod("getHandle");
                playerHandle = Class.forName(craft + ".entity.CraftPlayer").getMethod("getHandle");
                type = nms("world.entity.Entity").getMethod("getType");
                data = nms("world.entity.Entity").getMethod("getEntityData");
                values = nms("network.syncher.SynchedEntityData").getMethod("packAll");
                connection = nms("server.level.ServerPlayer").getField("connection");
                send = nms("server.network.ServerCommonPacketListenerImpl").getMethod("send", nms("network.protocol.Packet"));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Spectator avatars require Paper 26.2.", failure);
            }
        }
        private Object vector(double x, double y, double z) throws ReflectiveOperationException { return vector.newInstance(x, y, z); }
        private void send(Player viewer, Object packet) throws ReflectiveOperationException { send.invoke(connection.get(playerHandle.invoke(viewer)), packet); }
    }
    private final Transport transport;
    private final Allay entity;
    private Component name;
    private Object metadata;
    private final java.util.Map<java.util.UUID, Object> sentMetadata = new java.util.HashMap<>();
    public PaperAllayAvatar(Transport transport, Player owner) {
        this.transport = transport;
        entity = owner.getWorld().createEntity(owner.getLocation(), Allay.class);
        entity.setSilent(true);
        entity.setGravity(false);
        entity.setCustomNameVisible(true);
    }
    private Object metadata(Component next) throws ReflectiveOperationException {
        if (!next.equals(name)) {
            entity.customName(next);
            name = next;
            metadata = transport.metadata.newInstance(entity.getEntityId(),
                    transport.values.invoke(transport.data.invoke(transport.handle.invoke(entity))));
        }
        return metadata;
    }
    private void sendMetadata(Player viewer, Component name) throws ReflectiveOperationException {
        Object packet = metadata(name);
        if (sentMetadata.get(viewer.getUniqueId()) != packet) {
            transport.send(viewer, packet); sentMetadata.put(viewer.getUniqueId(), packet);
        }
    }
    @Override public void spawn(Player viewer, Location location, Component name) {
        sentMetadata.remove(viewer.getUniqueId());
        try {
            transport.send(viewer, transport.spawn.newInstance(entity.getEntityId(), entity.getUniqueId(),
                    location.getX(), location.getY(), location.getZ(), location.getPitch(), location.getYaw(),
                    transport.type.invoke(transport.handle.invoke(entity)), 0, transport.vector(0, 0, 0), (double) location.getYaw()));
            sendMetadata(viewer, name);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Could not show spectator allay.", failure); }
    }
    @Override public void move(Player viewer, Location location, Component name) {
        try {
            Object change = transport.position.newInstance(transport.vector(location.getX(), location.getY(), location.getZ()),
                    transport.vector(0, 0, 0), location.getYaw(), location.getPitch());
            transport.send(viewer, transport.teleport.newInstance(entity.getEntityId(), change, Set.of(), false));
            // The same cached packet is shared by every viewer, including after a tier refresh.
            sendMetadata(viewer, name);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Could not update spectator allay.", failure); }
    }
    @Override public void destroy(Player viewer) {
        sentMetadata.remove(viewer.getUniqueId());
        try { transport.send(viewer, transport.remove.newInstance((Object) new int[]{entity.getEntityId()})); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Could not remove spectator allay.", failure); }
    }
}
