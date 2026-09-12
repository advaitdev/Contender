package me.advait.contender.tab;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Binds UHCR Paper's tab API in the same way as UHCR's chat plugin. */
public final class ForkTabBridge implements TabBridge {
    private final MethodHandle set, clear, quit;
    public ForkTabBridge() {
        MethodHandle setter = null, clearer = null, quitter = null;
        try {
            Class<?> api = Class.forName("io.papermc.paper.uhcr.UhcrTabEntries");
            var lookup = MethodHandles.publicLookup();
            setter = lookup.findStatic(api, "setEntries", MethodType.methodType(void.class, Player.class,
                    UUID[].class, String[].class, Component[].class, int[].class, int[].class, String[].class, String[].class));
            clearer = lookup.findStatic(api, "clearEntries", MethodType.methodType(void.class, Player.class));
            quitter = lookup.findStatic(api, "handleQuit", MethodType.methodType(void.class, Player.class));
        } catch (ReflectiveOperationException | LinkageError unavailable) { setter = null; }
        set = setter; clear = clearer; quit = quitter;
    }
    @Override public boolean available() { return set != null; }
    @Override public void show(Player viewer, List<TabRow> rows) {
        if (!available()) return;
        if (rows.size() > 80) throw new IllegalArgumentException("Tab layouts cannot exceed 80 rows.");
        int count = rows.size();
        UUID[] ids = new UUID[count];
        String[] names = new String[count], textures = new String[count], signatures = new String[count];
        Component[] text = new Component[count];
        int[] latency = new int[count], order = new int[count];
        for (int i = 0; i < count; i++) {
            ids[i] = UUID.nameUUIDFromBytes(("contender-tab-slot-" + i).getBytes(StandardCharsets.UTF_8));
            names[i] = "ct_tab_" + i;
            TabRow row = rows.get(i);
            text[i] = row.text(); latency[i] = row.latency(); order[i] = 10000 - i;
            textures[i] = row.texture(); signatures[i] = row.signature();
        }
        try { set.invokeExact(viewer, ids, names, text, latency, order, textures, signatures); }
        catch (Throwable failure) { throw failed(failure); }
    }
    @Override public void clear(Player viewer) {
        if (!available()) return;
        try { clear.invokeExact(viewer); } catch (Throwable failure) { throw failed(failure); }
    }
    @Override public void quit(Player viewer) {
        if (!available()) return;
        try { quit.invokeExact(viewer); } catch (Throwable failure) { throw failed(failure); }
    }
    private static RuntimeException failed(Throwable failure) {
        if (failure instanceof Error error) throw error;
        return failure instanceof RuntimeException runtime ? runtime : new IllegalStateException("Could not update tab entries", failure);
    }
}
