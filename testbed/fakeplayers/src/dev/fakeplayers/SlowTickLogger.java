package dev.fakeplayers;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;

/** Appends every tick slower than the threshold to slow-ticks.log (epoch start/end millis) for matching against profiles. */
public class SlowTickLogger implements Listener {

    private static final double THRESHOLD_MS = Double.parseDouble(System.getProperty("fakeplayers.slowtick", "35"));

    private final PrintWriter out;
    private long tickStartMillis;

    public SlowTickLogger(FakePlayersPlugin plugin) throws IOException {
        this.out = new PrintWriter(new FileWriter(plugin.getDataFolder().toPath().resolve("slow-ticks.log").toFile(), true), true);
    }

    @EventHandler
    public void onTickStart(ServerTickStartEvent event) {
        this.tickStartMillis = System.currentTimeMillis();
    }

    @EventHandler
    public void onTickEnd(ServerTickEndEvent event) {
        if (event.getTickDuration() >= THRESHOLD_MS) {
            this.out.println(event.getTickNumber() + " " + Instant.ofEpochMilli(this.tickStartMillis) + " " + String.format("%.1f", event.getTickDuration()));
        }
    }
}
