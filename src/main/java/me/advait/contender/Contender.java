package me.advait.contender;

import me.advait.contender.command.ContenderCommand;
import me.advait.contender.command.DuelCommand;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.kit.KitManager;
import me.advait.contender.listener.*;
import me.advait.contender.map.MapManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class Contender extends JavaPlugin {

    private DuelManager duelManager;
    private KitManager kitManager;
    private MapManager mapManager;
    private final Map<UUID, Consumer<String>> chatInputHandlers = new HashMap<>();

    @Override
    public void onEnable() {
        kitManager = new KitManager(this);
        mapManager = new MapManager(this);
        duelManager = new DuelManager(this);

        registerListeners();
        registerCommands();

        getLogger().info("Contender enabled.");
    }

    @Override
    public void onDisable() {
        if (duelManager != null) {
            duelManager.cleanup();
        }
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerNoxesiumListener(), this);
        getServer().getPluginManager().registerEvents(
                new DuelListener(this, duelManager), this);
        getServer().getPluginManager().registerEvents(new ChatListener(), this);
        getServer().getPluginManager().registerEvents(
                new ArenaProtectionListener(duelManager), this);
        getServer().getPluginManager().registerEvents(
                new GUIListener(this, duelManager, kitManager, mapManager), this);
    }

    private void registerCommands() {
        var duelCmd = getCommand("duel");
        if (duelCmd != null) {
            duelCmd.setExecutor(new DuelCommand(this, duelManager));
        }
        var contenderCmd = getCommand("contender");
        if (contenderCmd != null) {
            contenderCmd.setExecutor(new ContenderCommand(this));
        }
    }

    public void awaitChatInput(UUID uuid, Consumer<String> handler) {
        chatInputHandlers.put(uuid, handler);
    }

    public Consumer<String> pollChatInput(UUID uuid) {
        return chatInputHandlers.remove(uuid);
    }

    public DuelManager getDuelManager() {
        return duelManager;
    }

    public KitManager getKitManager() {
        return kitManager;
    }

    public MapManager getMapManager() {
        return mapManager;
    }
}
