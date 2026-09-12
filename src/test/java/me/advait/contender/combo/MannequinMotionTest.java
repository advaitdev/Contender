package me.advait.contender.combo;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MannequinMotionTest {
    public interface HandleAccess { NativeHandle getHandle(); }
    public static final class NativeHandle {
        public float zza, xxa, speed;
        public void setSpeed(float speed) { this.speed = speed; }
    }

    private final NativeHandle handle = new NativeHandle();
    private Mannequin bot;
    private Player target;
    private Location position;
    private BoundingBox targetBox;
    private MannequinMotion motion;

    @BeforeEach void setup() {
        bot = mock(Mannequin.class, withSettings().extraInterfaces(HandleAccess.class));
        target = mock(Player.class);
        position = new Location(mock(World.class), 0, 64, 0);
        targetBox = new BoundingBox(-0.3, 64, 3.7, 0.3, 65.8, 4.3);
        when(((HandleAccess) bot).getHandle()).thenReturn(handle);
        when(bot.getLocation()).thenAnswer(call -> position.clone());
        when(bot.getEyeLocation()).thenAnswer(call -> position.clone().add(0, 1.62, 0));
        when(target.getBoundingBox()).thenAnswer(call -> targetBox);
        when(bot.isOnGround()).thenReturn(true);
        doAnswer(call -> {
            position.setYaw(call.getArgument(0));
            position.setPitch(call.getArgument(1));
            return null;
        }).when(bot).setRotation(anyFloat(), anyFloat());
        motion = new MannequinMotion(bot);
    }

    @Test void turnsGraduallyAndDoesNotAdvanceWhileFacingAway() {
        targetBox = new BoundingBox(3.7, 67, -0.3, 4.3, 68.8, 0.3);

        motion.drive(bot, target, ComboDifficulty.EASY, true);

        assertEquals(-8, position.getYaw(), 0.0001);
        assertEquals(-8, position.getPitch(), 0.0001);
        assertEquals(0, handle.zza);
        assertEquals(0, handle.xxa);

        for (int i = 0; i < 3; i++) motion.drive(bot, target, ComboDifficulty.EASY, true);
        assertEquals(-32, position.getYaw(), 0.0001);
        assertEquals(0.15, handle.zza, 0.0001);
    }

    @Test void takesShortestYawPathAcrossWrapWithoutSnapping() {
        position.setYaw(178);
        var direction = new Location(position.getWorld(), 0, 0, 0, -170, 0).getDirection();
        var center = direction.multiply(4).add(position.toVector()).add(new Vector(0, 1.62, 0));
        targetBox = BoundingBox.of(center, 0.3, 0.3, 0.3);

        motion.drive(bot, target, ComboDifficulty.EASY, true);

        assertEquals(-174, position.getYaw(), 0.0001);
        motion.drive(bot, target, ComboDifficulty.EASY, true);
        assertEquals(-170, position.getYaw(), 0.0001);
    }

    @Test void aimsFromEyesAtBodyCenter() {
        var expected = bot.getEyeLocation().setDirection(targetBox.getCenter().subtract(bot.getEyeLocation().toVector()));
        for (int i = 0; i < 20; i++) motion.drive(bot, target, ComboDifficulty.EASY, false);

        assertEquals(expected.getYaw(), position.getYaw(), 0.0001);
        assertEquals(expected.getPitch(), position.getPitch(), 0.0001);
        assertTrue(position.getPitch() > 0, "Aim should tilt down from the eyes to the body, not aim along the feet.");
        assertEquals(0, handle.zza);
    }

    @Test void buildsTravelInputGraduallyToItsLimit() {
        motion.drive(bot, target, ComboDifficulty.NORMAL, true);
        assertEquals(0.15, handle.zza, 0.0001);
        motion.drive(bot, target, ComboDifficulty.NORMAL, true);
        assertEquals(0.30, handle.zza, 0.0001);
        for (int i = 0; i < 10; i++) motion.drive(bot, target, ComboDifficulty.NORMAL, true);
        assertEquals(1, handle.zza, 0.0001);
        assertEquals(ComboDifficulty.NORMAL.speed(), handle.speed);
    }

    @Test void softensAirControlWithoutChangingNativeVelocity() {
        for (int i = 0; i < 10; i++) motion.drive(bot, target, ComboDifficulty.NORMAL, true);
        assertEquals(1, handle.zza, 0.0001);

        when(bot.isOnGround()).thenReturn(false);
        motion.drive(bot, target, ComboDifficulty.NORMAL, true);
        assertEquals(ComboDifficulty.NORMAL.airControl(), handle.zza, 0.0001);
        assertEquals(ComboDifficulty.NORMAL.speed(), handle.speed);

        when(bot.isOnGround()).thenReturn(true);
        motion.drive(bot, target, ComboDifficulty.NORMAL, true);
        assertEquals(1, handle.zza, 0.0001);
        verify(bot, never()).setVelocity(any());
        verify(bot, never()).teleport(any(Location.class));
        verify(bot, never()).setImmovable(anyBoolean());
    }

    @Test void recoveryImmediatelyReleasesInputAndRestartsAcceleration() {
        for (int i = 0; i < 10; i++) motion.drive(bot, target, ComboDifficulty.NORMAL, true);
        motion.drive(bot, target, ComboDifficulty.NORMAL, false);

        assertEquals(0, handle.zza);
        assertEquals(0, handle.xxa);
        verify(bot, never()).setVelocity(any());

        motion.drive(bot, target, ComboDifficulty.NORMAL, true);
        assertEquals(0.15, handle.zza, 0.0001);
    }
}
