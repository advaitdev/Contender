package dev.fakeplayers;

import io.papermc.paper.adventure.PaperAdventure;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.network.chat.Component;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records what bots are shown (chat, titles, optionally the action bar) to plugins/FakePlayers/chat.log, so
 * you can check what players actually see. Identical messages shown to many bots within one flush window
 * become one line with a recipient count.
 *
 * <p>Line: {@code HH:mm:ss.SSS <kind> n=<recipients> to=<a bot> | <plain> | <json>}
 */
final class ChatCapture {

    enum Mode { OFF, CHAT, ALL }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static volatile Mode mode = Mode.OFF;

    private record Key(String kind, String json) { }
    private static final class Seen {
        final String time = LocalTime.now().format(TIME);
        final String plain;
        final String sample;
        int count;
        Seen(String plain, String sample) { this.plain = plain; this.sample = sample; }
    }

    private static final Map<Key, Seen> pending = new LinkedHashMap<>();
    /** Lines already serialized this flush window, by kind and plain text. */
    private static final Map<String, Key> byPlain = new java.util.HashMap<>();
    private static PrintWriter out;

    private ChatCapture() { }

    static Mode mode() { return mode; }

    /** Each "on" reopens chat.log, so a test may delete it between runs; "off" flushes and closes it. */
    static void setMode(FakePlayersPlugin plugin, Mode newMode) {
        synchronized (pending) {
            flush(true);
            if (out != null) {
                out.println("# " + LocalTime.now().format(TIME) + " capture " + newMode);
                out.close();
                out = null;
            }
            mode = Mode.OFF;
            pending.clear();
            byPlain.clear();
            if (newMode == Mode.OFF) return;
            try {
                out = new PrintWriter(new FileWriter(plugin.getDataFolder().toPath().resolve("chat.log").toFile(), true), false);
            } catch (IOException e) {
                plugin.getLogger().warning("Chat capture unavailable: " + e);
                return;
            }
            out.println("# " + LocalTime.now().format(TIME) + " capture " + newMode);
            out.flush();
            mode = newMode;
        }
    }

    /** Any thread: the packet sender may be off the main thread. */
    static void record(Bot bot, String kind, Component component) {
        Mode m = mode;
        if (m == Mode.OFF || component == null) return;
        if (kind.equals("actionbar") && m != Mode.ALL) return;
        // A broadcast reaches hundreds of bots: serialize each distinct line once per flush window,
        // then only count, so capturing doesn't cost the server more than the message itself.
        String plain = component.getString();
        synchronized (pending) {
            Key known = byPlain.get(kind + '\u0000' + plain);
            if (known != null) {
                pending.get(known).count++;
                return;
            }
        }
        net.kyori.adventure.text.Component adventure = PaperAdventure.asAdventure(component);
        String json = GsonComponentSerializer.gson().serialize(adventure);
        synchronized (pending) {
            Key key = new Key(kind, json);
            pending.computeIfAbsent(key,
                    k -> new Seen(PlainTextComponentSerializer.plainText().serialize(adventure), bot.getGameProfile().name())).count++;
            byPlain.putIfAbsent(kind + '\u0000' + plain, key);
        }
    }

    /** Main thread, once a second. */
    static void flush(boolean all) {
        synchronized (pending) {
            if (out == null || pending.isEmpty()) return;
            for (Iterator<Map.Entry<Key, Seen>> it = pending.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Key, Seen> e = it.next();
                Seen s = e.getValue();
                out.println(s.time + " " + e.getKey().kind() + " n=" + s.count + " to=" + s.sample
                        + " | " + s.plain.replace('\n', '⏎') + " | " + e.getKey().json());
                it.remove();
            }
            byPlain.clear();
            out.flush();
        }
    }

    static void close() {
        synchronized (pending) {
            flush(true);
            if (out != null) out.close();
            out = null;
            mode = Mode.OFF;
        }
    }
}
