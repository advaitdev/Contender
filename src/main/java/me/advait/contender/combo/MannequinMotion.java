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
    private float forwardInput;
    MannequinMotion(Mannequin bot) {
        try {
            handle = bot.getClass().getMethod("getHandle").invoke(bot);
            forward = handle.getClass().getField("zza"); sideways = handle.getClass().getField("xxa");
            speed = handle.getClass().getMethod("setSpeed", float.class);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("This Paper build cannot move the Combo mannequin.", failure); }
    }
    void drive(Mannequin bot, Player target, ComboDifficulty difficulty, boolean walking) {
        var facing = bot.getEyeLocation();
        var direction = target.getBoundingBox().getCenter().subtract(facing.toVector());
        float yaw = facing.getYaw();
        float pitch = facing.getPitch();
        float remainingYaw = 0;
        if (direction.lengthSquared() > 0.0001) {
            var desired = facing.clone().setDirection(direction);
            float turn = difficulty.turnDegrees();
            yaw = wrap(yaw + Math.clamp(wrap(desired.getYaw() - yaw), -turn, turn));
            pitch += Math.clamp(desired.getPitch() - pitch, -turn, turn);
            remainingYaw = wrap(desired.getYaw() - yaw);
            bot.setRotation(yaw, pitch);
        }
        // Releasing travel input leaves knockback and existing momentum to vanilla physics.
        boolean advancing = walking && Math.abs(remainingYaw) <= 60;
        forwardInput = advancing ? Math.min(1, forwardInput + 0.15f) : 0;
        float input = forwardInput * (bot.isOnGround() ? 1 : difficulty.airControl());
        try {
            forward.setFloat(handle, input); sideways.setFloat(handle, 0); speed.invoke(handle, difficulty.speed());
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Could not move the Combo mannequin.", failure); }
    }
    private static float wrap(float angle) {
        angle %= 360;
        if (angle >= 180) angle -= 360;
        if (angle < -180) angle += 360;
        return angle;
    }
}
