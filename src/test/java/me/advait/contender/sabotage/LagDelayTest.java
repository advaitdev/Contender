package me.advait.contender.sabotage;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LagDelayTest {
    @Test void holdsPacketsBothWaysAndKeepsTheirOrder() {
        EmbeddedChannel channel = new EmbeddedChannel(new LagInjector.Delay(50));
        channel.writeOutbound("first", "second");
        channel.writeInbound("incoming");
        assertNull(channel.readOutbound());
        assertNull(channel.readInbound());

        channel.advanceTimeBy(49, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertNull(channel.readOutbound(), "released too early");

        channel.advanceTimeBy(2, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertEquals("first", channel.readOutbound());
        assertEquals("second", channel.readOutbound());
        assertEquals("incoming", channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test void removingTheHandlerReleasesHeldPacketsFirst() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("lag", new LagInjector.Delay(20));
        channel.writeOutbound("held");
        channel.writeInbound("held in");
        channel.pipeline().remove("lag");
        channel.writeOutbound("after");
        channel.writeInbound("after in");
        assertEquals("held", channel.readOutbound());
        assertEquals("after", channel.readOutbound());
        assertEquals("held in", channel.readInbound());
        assertEquals("after in", channel.readInbound());
        // The timers that would have released them find nothing left to send.
        channel.advanceTimeBy(25, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertNull(channel.readOutbound());
        assertNull(channel.readInbound());
        channel.finishAndReleaseAll();
    }
}
