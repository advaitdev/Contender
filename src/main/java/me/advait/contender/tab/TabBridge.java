package me.advait.contender.tab;

import org.bukkit.entity.Player;
import java.util.List;

/** Separates the layout from the server's packet transport. Calls run on the server thread. */
public interface TabBridge {
    boolean available();
    void show(Player viewer, List<TabRow> rows);
    void clear(Player viewer);
    void quit(Player viewer);
}
