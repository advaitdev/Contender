package me.advait.contender.tournament;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoardHitboxTest {
    @Test void findsThePointedRowForEveryCardinalOrientation() {
        for (int yaw : new int[]{0, 90, 180, 270}) {
            double angle = Math.toRadians(yaw);
            Vector normal = new Vector(-Math.sin(angle), 0, Math.cos(angle));
            Vector right = new Vector(Math.cos(angle), 0, Math.sin(angle));
            Vector anchor = new Vector(20, 70, -10);
            Vector point = anchor.clone().add(right.clone().multiply(3)).add(new Vector(0, -2, 0));
            Vector eye = point.clone().add(normal.clone().multiply(8));
            var hit = BoardHitbox.intersect(eye, normal.clone().multiply(-1), anchor, normal, right, 24);
            assertNotNull(hit); assertEquals(3, hit.x(), 1e-8); assertEquals(-2, hit.y(), 1e-8); assertEquals(8, hit.distance(), 1e-8);
            assertTrue(BoardHitbox.contains(hit, 3, -2, 4, .255));
            assertFalse(BoardHitbox.contains(hit, 3, -1.745, 4, .255));
        }
    }
    @Test void cannotSelectFromBehindParallelToTheBoardOrBeyondReach() {
        Vector anchor = new Vector(), normal = new Vector(0, 0, 1), right = new Vector(1, 0, 0);
        assertNull(BoardHitbox.intersect(new Vector(0, 0, -4), normal, anchor, normal, right, 24));
        assertNull(BoardHitbox.intersect(new Vector(0, 0, 4), right, anchor, normal, right, 24));
        assertNull(BoardHitbox.intersect(new Vector(0, 0, 25), normal.clone().multiply(-1), anchor, normal, right, 24));
    }
}
