package me.advait.contender;

import me.advait.contender.command.*;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.kit.KitManager;
import me.advait.contender.listener.*;
import me.advait.contender.map.MapManager;
import me.advait.contender.runnable.ImmediateRespawnRunnable;
import me.advait.contender.vote.VoteManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class Contender extends JavaPlugin {

    private DuelManager duelManager;
    private KitManager kitManager;
    private MapManager mapManager;
    private VoteManager voteManager;
    private LobbyManager lobbyManager;
    private SpectatorManager spectatorManager;
    private PlayerSettingsManager playerSettingsManager;
    private final Map<UUID, Consumer<String>> chatInputHandlers = new HashMap<>();

    @Override
    public void onEnable() {
        spectatorManager = new SpectatorManager(this);
        playerSettingsManager = new PlayerSettingsManager(this);
        lobbyManager = new LobbyManager(this, spectatorManager);
        kitManager = new KitManager(this);
        mapManager = new MapManager(this);
        duelManager = new DuelManager(this, spectatorManager);
        voteManager = new VoteManager(this, duelManager);

        registerListeners();
        registerCommands();
        registerRunnables();

        getLogger().info("Contender enabled.");
    }

    @Override
    public void onDisable() {
        if (voteManager != null && voteManager.isVoteActive()) {
            voteManager.getActiveSession().end();
        }
        if (duelManager != null) {
            duelManager.cleanup();
        }
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerNoxesiumListener(), this);
        getServer().getPluginManager().registerEvents(new LeafDecayListener(), this);
        getServer().getPluginManager().registerEvents(
                new DuelListener(this, duelManager), this);
        getServer().getPluginManager().registerEvents(new ChatListener(), this);
        getServer().getPluginManager().registerEvents(
                new ArenaProtectionListener(duelManager), this);
        getServer().getPluginManager().registerEvents(
                new GUIListener(this, duelManager, kitManager, mapManager, voteManager, spectatorManager, playerSettingsManager), this);
        getServer().getPluginManager().registerEvents(
                new VoteListener(voteManager, spectatorManager), this);
        getServer().getPluginManager().registerEvents(
                new LobbyListener(this, lobbyManager), this);
    }

    private void registerCommands() {
        var duelCmd = getCommand("duel");
        if (duelCmd != null) duelCmd.setExecutor(new DuelCommand(this, duelManager));

        var contenderCmd = getCommand("contender");
        if (contenderCmd != null) contenderCmd.setExecutor(new ContenderCommand(this, spectatorManager));

        var endDuelCmd = getCommand("endduel");
        if (endDuelCmd != null) endDuelCmd.setExecutor(new EndDuelCommand(duelManager));

        var startVoteCmd = getCommand("startvote");
        if (startVoteCmd != null) startVoteCmd.setExecutor(new StartVoteCommand(voteManager));

        var endVoteCmd = getCommand("endvote");
        if (endVoteCmd != null) endVoteCmd.setExecutor(new EndVoteCommand(voteManager));

        var voteCmd = getCommand("vote");
        if (voteCmd != null) voteCmd.setExecutor(new VoteCommand(voteManager, spectatorManager));

        var spectatorCmd = getCommand("spectator");
        if (spectatorCmd != null) {
            SpectatorCommand spectatorCommand = new SpectatorCommand(spectatorManager);
            spectatorCmd.setExecutor(spectatorCommand);
            spectatorCmd.setTabCompleter(spectatorCommand);
        }

        var settingsCmd = getCommand("settings");
        if (settingsCmd != null) settingsCmd.setExecutor(new SettingsCommand(playerSettingsManager));
    }

    private void registerRunnables() {
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new ImmediateRespawnRunnable(), 0, 20);
    }

    public void awaitChatInput(UUID uuid, Consumer<String> handler) {
        chatInputHandlers.put(uuid, handler);
    }

    public Consumer<String> pollChatInput(UUID uuid) {
        return chatInputHandlers.remove(uuid);
    }

    public DuelManager getDuelManager() { return duelManager; }
    public KitManager getKitManager() { return kitManager; }
    public MapManager getMapManager() { return mapManager; }
    public VoteManager getVoteManager() { return voteManager; }
    public LobbyManager getLobbyManager() { return lobbyManager; }
    public SpectatorManager getSpectatorManager() { return spectatorManager; }
    public PlayerSettingsManager getPlayerSettingsManager() { return playerSettingsManager; }
}
