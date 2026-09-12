package me.advait.contender.combo;

import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Feeds vanilla travel input instead of replacing velocity, preserving hit knockback and gravity. */
final class MannequinMotion {
    private final Object handle;
    private final Field forward, sideways;
    private final Method speed;
    MannequinMotion(Mannequin bot) {
        try {
            handle = bot.getClass().getMethod("getHandle").invoke(bot);
            forward = handle.getClass().getField("zza"); sideways = handle.getClass().getField("xxa");
            speed = handle.getClass().getMethod("setSpeed", float.class);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("This Paper build cannot move the Combo mannequin.", failure); }
    }
    void drive(Mannequin bot, Player target, float movementSpeed, boolean walking) {
        var direction = target.getLocation().toVector().subtract(bot.getLocation().toVector());
        if (direction.lengthSquared() > 0.0001) {
            var facing = bot.getLocation().setDirection(direction); bot.setRotation(facing.getYaw(), facing.getPitch());
        }
        try {
            forward.setFloat(handle, walking ? 1 : 0); sideways.setFloat(handle, 0); speed.invoke(handle, movementSpeed);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Could not move the Combo mannequin.", failure); }
    }
}
