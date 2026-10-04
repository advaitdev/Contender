package me.advait.contender.minigame.race;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class RaceCourseTest {
    private static World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> null;
        });
    }

    private final World world = world("course");

    private Location at(double y) { return new Location(world, 0, y, 0); }

    @Test void lastCheckpointIsTheFinish() {
        RaceCourse course = new RaceCourse("test", "Test", "course");
        course.add(at(1), EntityType.ZOMBIE);
        assertEquals("Finish", course.label(0));
        course.add(at(2), EntityType.ZOMBIE);
        assertEquals("#1", course.label(0));
        assertEquals("Finish", course.label(1));
    }

    @Test void insertMoveAndRemoveRenumber() {
        RaceCourse course = new RaceCourse("test", "Test", "course");
        course.add(at(1), EntityType.ZOMBIE);
        course.add(at(3), EntityType.ZOMBIE);
        course.insert(1, at(2), EntityType.HUSK);
        assertEquals(EntityType.HUSK, course.checkpoints().get(1).type());
        assertEquals(2, course.checkpoints().get(1).y());
        course.move(1, at(5));
        assertEquals(5, course.checkpoints().get(1).y());
        assertEquals(EntityType.HUSK, course.checkpoints().get(1).type());
        course.remove(0);
        assertEquals(2, course.size());
        assertEquals(5, course.checkpoints().getFirst().y());
    }

    @Test void checkpointsStayInTheCourseWorld() {
        RaceCourse course = new RaceCourse("test", "Test", "course");
        course.add(at(1), EntityType.ZOMBIE);
        assertThrows(IllegalArgumentException.class, () -> course.add(new Location(world("elsewhere"), 0, 0, 0), EntityType.ZOMBIE));
    }

    @Test void rulesAreValidated() {
        RaceCourse course = new RaceCourse("test", "Test", "course");
        assertThrows(IllegalArgumentException.class, () -> course.rules(0, 12, true));
        assertThrows(IllegalArgumentException.class, () -> course.rules(5, 0.5, true));
        assertThrows(IllegalArgumentException.class, () -> course.rules(5, Double.NaN, true));
        course.rules(3, 8, false);
        assertEquals(3, course.maxJump());
        assertFalse(course.returnOnGround());
    }

    @Test void raceTimesFormat() {
        assertEquals("0:00.000", RaceGame.time(0));
        assertEquals("1:05.250", RaceGame.time(65_250_000_000L));
    }
}
