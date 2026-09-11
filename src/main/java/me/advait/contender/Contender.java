package me.advait.contender;

import me.advait.contender.chat.ChatListener;
import me.advait.contender.chat.ChatManager;
import me.advait.contender.chat.ChatSettings;
import me.advait.contender.command.*;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.arena.ArenaManager;
import me.advait.contender.tournament.TournamentManager;
import me.advait.contender.dialog.ArenaDialogs;
import me.advait.contender.dialog.TournamentDialogs;
import me.advait.contender.dialog.DuelDialogs;
import me.advait.contender.role.RoleManager;
import me.advait.contender.nametag.NameTagManager;
import me.advait.contender.tier.TierService;
import me.advait.contender.gui.GUIListener;
import me.advait.contender.kit.KitManager;
import me.advait.contender.lobby.LobbyListener;
import me.advait.contender.lobby.LobbyManager;
import me.advait.contender.map.MapManager;
import me.advait.contender.player.PlayerSettingsManager;
import me.advait.contender.pvp.PvPListener;
import me.advait.contender.pvp.PvPSettings;
import me.advait.contender.runnable.ImmediateRespawnRunnable;
import me.advait.contender.spectator.ArenaProtectionListener;
import me.advait.contender.spectator.SpectatorManager;
import me.advait.contender.voice.VoiceChatManager;
import me.advait.contender.vote.VoteListener;
import me.advait.contender.vote.VoteManager;
import me.advait.contender.world.LeafDecayListener;
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
    private ArenaManager arenaManager;
    private TournamentManager tournamentManager;
    private VoteManager voteManager;
    private LobbyManager lobbyManager;
    private SpectatorManager spectatorManager;
    private PlayerSettingsManager playerSettingsManager;
    private ChatSettings chatSettings;
    private PvPSettings pvpSettings;
    private RoleManager roleManager;
    private TierService tierService;
    private NameTagManager nameTagManager;
    private final Map<UUID, Consumer<String>> chatInputHandlers = new HashMap<>();

    @Override
    public void onEnable() {
        roleManager = new RoleManager(this);
        tierService = new TierService(this);
        nameTagManager = new NameTagManager(this);
        spectatorManager = new SpectatorManager(this);
        playerSettingsManager = new PlayerSettingsManager(this);
        lobbyManager = new LobbyManager(this, spectatorManager);
        kitManager = new KitManager(this);
        mapManager = new MapManager(this);
        arenaManager = new ArenaManager(this, mapManager);
        duelManager = new DuelManager(this, spectatorManager);
        voteManager = new VoteManager(this, duelManager);
        chatSettings = new ChatSettings(this);
        pvpSettings = new PvPSettings(this);
        tournamentManager = new TournamentManager(this);

        arenaManager.initialize();

        registerListeners();
        registerCommands();
        registerRunnables();
        tournamentManager.enable();
        nameTagManager.enable();

        VoiceChatManager.setup(this, chatSettings, duelManager);

        getLogger().info("Contender enabled.");
    }

    @Override
    public void onDisable() {
        if (nameTagManager != null) nameTagManager.disable();
        if (tierService != null) tierService.close();
        if (tournamentManager != null) tournamentManager.disable();
        if (voteManager != null && voteManager.isVoteActive()) {
            voteManager.getActiveSession().end();
        }
        if (duelManager != null) {
            duelManager.shutdown();
        }
        if (arenaManager != null) arenaManager.shutdown();
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new LeafDecayListener(), this);
        getServer().getPluginManager().registerEvents(new ChatListener(), this);
        getServer().getPluginManager().registerEvents(
                new ChatManager(chatSettings, duelManager, spectatorManager), this);
        getServer().getPluginManager().registerEvents(
                new PvPListener(pvpSettings, duelManager, spectatorManager), this);
        getServer().getPluginManager().registerEvents(
                new ArenaProtectionListener(duelManager, arenaManager), this);
        getServer().getPluginManager().registerEvents(
                new GUIListener(this, duelManager, kitManager, mapManager, voteManager, spectatorManager, playerSettingsManager, chatSettings, pvpSettings), this);
        getServer().getPluginManager().registerEvents(
                new VoteListener(voteManager, spectatorManager), this);
        getServer().getPluginManager().registerEvents(
                new LobbyListener(this, lobbyManager), this);
    }

    private void registerCommands() {
        RoleCommand roleCommand = new RoleCommand(this);
        getCommand("role").setExecutor(roleCommand);
        getCommand("role").setTabCompleter(roleCommand);
        getCommand("tier").setExecutor(new TierCommand(this));
        getCommand("arena").setExecutor(new DialogCommand(true, new ArenaDialogs(this)::open));
        getCommand("tournament").setExecutor(new DialogCommand(true, new TournamentDialogs(this)::open));
        getCommand("spectate").setExecutor(new DialogCommand(false, new DuelDialogs(this)::spectate));
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

        var deceasedCmd = getCommand("deceased");
        if (deceasedCmd != null) {
            DeceasedCommand deceasedCommand = new DeceasedCommand(spectatorManager);
            deceasedCmd.setExecutor(deceasedCommand);
            deceasedCmd.setTabCompleter(deceasedCommand);
        }

        var settingsCmd = getCommand("settings");
        if (settingsCmd != null) settingsCmd.setExecutor(new SettingsCommand(this));
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
    public ArenaManager getArenaManager() { return arenaManager; }
    public TournamentManager getTournamentManager() { return tournamentManager; }
    public VoteManager getVoteManager() { return voteManager; }
    public LobbyManager getLobbyManager() { return lobbyManager; }
    public SpectatorManager getSpectatorManager() { return spectatorManager; }
    public PlayerSettingsManager getPlayerSettingsManager() { return playerSettingsManager; }
    public ChatSettings getChatSettings() { return chatSettings; }
    public PvPSettings getPvpSettings() { return pvpSettings; }
    public RoleManager getRoleManager() { return roleManager; }
    public TierService getTierService() { return tierService; }
    public NameTagManager getNameTagManager() { return nameTagManager; }
}
