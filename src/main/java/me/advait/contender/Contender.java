package me.advait.contender;

import me.advait.contender.tab.TabManager;
import me.advait.contender.dialog.BracketDialogs;
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
import me.advait.contender.spectator.PaperAllayAvatar;
import me.advait.contender.spectator.AllayAvatar;
import me.advait.contender.spectator.SpectatorControls;
import me.advait.contender.pvp.PvPListener;
import me.advait.contender.pvp.PvPSettings;
import me.advait.contender.runnable.ImmediateRespawnRunnable;
import me.advait.contender.spectator.ArenaProtectionListener;
import me.advait.contender.voice.VoiceChatManager;
import me.advait.contender.vote.VoteListener;
import me.advait.contender.vote.VoteManager;
import me.advait.contender.world.LeafDecayListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;


public final class Contender extends JavaPlugin {

    private me.advait.contender.hacker.HackerManager hackerManager;
    private me.advait.contender.race.RaceManager raceManager;
    private me.advait.contender.minigame.MinigameManager minigameManager;
    private me.advait.contender.manhunt.ManhuntManager manhuntManager;
    private me.advait.contender.combo.ComboManager comboManager;
    private DuelManager duelManager;
    private KitManager kitManager;
    private MapManager mapManager;
    private ArenaManager arenaManager;
    private TournamentManager tournamentManager;
    private VoteManager voteManager;
    private LobbyManager lobbyManager;
    private PaperAllayAvatar.Transport allayTransport;
    private SpectatorControls spectatorControls;
    private ChatSettings chatSettings;
    private PvPSettings pvpSettings;
    private RoleManager roleManager;
    private TierService tierService;
    private NameTagManager nameTagManager;
    private TabManager tabManager;
    private me.advait.contender.voice.VoiceRouting voiceRouting;

    @Override
    public void onEnable() {
        roleManager = new RoleManager(this);
        tierService = new TierService(this);
        nameTagManager = new NameTagManager(this);
        allayTransport = new PaperAllayAvatar.Transport();
        lobbyManager = new LobbyManager(this);
        kitManager = new KitManager(this);
        mapManager = new MapManager(this);
        arenaManager = new ArenaManager(this, mapManager);
        duelManager = new DuelManager(this);
        voteManager = new VoteManager(this, duelManager);
        chatSettings = new ChatSettings(this);
        pvpSettings = new PvPSettings(this);
        tournamentManager = new TournamentManager(this);
        raceManager = new me.advait.contender.race.RaceManager(this);
        minigameManager = new me.advait.contender.minigame.MinigameManager(this);
        manhuntManager = new me.advait.contender.manhunt.ManhuntManager(this);
        comboManager = new me.advait.contender.combo.ComboManager(this);
        minigameManager.register(new me.advait.contender.minigame.RaceMode(this));
        minigameManager.register(manhuntManager);
        minigameManager.register(comboManager);
        hackerManager = new me.advait.contender.hacker.HackerManager(this);

        arenaManager.initialize();

        registerListeners();
        registerCommands();
        registerRunnables();
        tabManager = new TabManager(this);
        spectatorControls = new SpectatorControls(this);
        spectatorControls.enable();
        hackerManager.enable();
        raceManager.enable();
        manhuntManager.enable();
        comboManager.enable();
        tournamentManager.enable();
        nameTagManager.enable();
        tabManager.enable();
        for (var player : getServer().getOnlinePlayers()) roleManager.applySpectatorRole(player);

        voiceRouting = new me.advait.contender.voice.VoiceRouting(this);
        voiceRouting.enable();
        if (getServer().getPluginManager().isPluginEnabled("voicechat")) VoiceChatManager.setup(this);

        getLogger().info("Contender enabled.");
    }

    @Override
    public void onDisable() {
        if (comboManager != null) comboManager.disable();
        if (manhuntManager != null) manhuntManager.disable();
        if (raceManager != null) raceManager.disable();
        if (hackerManager != null) hackerManager.disable();
        if (voiceRouting != null) voiceRouting.disable();
        if (tabManager != null) tabManager.disable();
        if (nameTagManager != null) nameTagManager.disable();
        if (tierService != null) tierService.close();
        if (tournamentManager != null) tournamentManager.disable();
        if (voteManager != null && voteManager.isVoteActive()) {
            voteManager.getActiveSession().end();
        }
        if (voteManager != null) voteManager.timer().hide();
        if (duelManager != null) {
            duelManager.shutdown();
        }
        if (spectatorControls != null) spectatorControls.disable();
        if (arenaManager != null) arenaManager.shutdown();
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new LeafDecayListener(), this);
        getServer().getPluginManager().registerEvents(new ChatListener(), this);
        getServer().getPluginManager().registerEvents(
                new ChatManager(chatSettings, duelManager, roleManager, () -> voiceRouting), this);
        getServer().getPluginManager().registerEvents(
                new PvPListener(pvpSettings, duelManager, roleManager, minigameManager), this);
        getServer().getPluginManager().registerEvents(
                new ArenaProtectionListener(duelManager, arenaManager, raceManager), this);
        getServer().getPluginManager().registerEvents(
                new GUIListener(this), this);
        getServer().getPluginManager().registerEvents(
                new VoteListener(voteManager), this);
        getServer().getPluginManager().registerEvents(
                new LobbyListener(this, lobbyManager), this);
    }

    private void registerCommands() {
        var commands = new PaperCommands(this);
        commands.command("votetimer").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof org.bukkit.entity.Player player)) return true;
            if (!player.hasPermission("contender.master")) return true;
            try {
                if (args.length == 1 && args[0].equalsIgnoreCase("remove")) { voteManager.timer().remove(); me.advait.contender.dialog.Dialogs.tell(player, "Vote timer removed."); }
                else if (args.length == 0) { voteManager.timer().place(player); me.advait.contender.dialog.Dialogs.tell(player, "Timer placed. It appears during votes."); }
                else me.advait.contender.dialog.Dialogs.tell(player, "Use /votetimer or /votetimer remove.");
            } catch (IllegalArgumentException failure) { me.advait.contender.dialog.Dialogs.tell(player, failure.getMessage()); }
            return true;
        });
        commands.command("hacks").setExecutor(new DialogCommand(false, hackerManager.dialogs()::open));
        var pickHacker = new PickHackerCommand(this);
        commands.command("pickhacker").setExecutor(pickHacker);
        commands.command("pickhacker").setTabCompleter(pickHacker);
        commands.command("setlobby").setExecutor(new DialogCommand(true, player -> {
            getLobbyManager().setLobbyLocation(player.getLocation());
            me.advait.contender.dialog.Dialogs.tell(player, "Lobby set here.");
        }));
        commands.command("lobby").setExecutor(new DialogCommand(false, player -> getLobbyManager().returnToLobby(player)));
        commands.command("bracket").setExecutor(new DialogCommand(false, new BracketDialogs(this)::open));
        RoleCommand roleCommand = new RoleCommand(this);
        commands.command("role").setExecutor(roleCommand);
        commands.command("role").setTabCompleter(roleCommand);
        commands.command("tier").setExecutor(new TierCommand(this));
        commands.command("arena").setExecutor(new DialogCommand(true, new ArenaDialogs(this)::open));
        commands.command("tournament").setExecutor(new DialogCommand(true, new TournamentDialogs(this)::open));
        commands.command("spectate").setExecutor(new DialogCommand(false, new DuelDialogs(this)::spectate));
        var duelCmd = commands.command("duel");
        if (duelCmd != null) duelCmd.setExecutor(new DuelCommand(this, duelManager));

        var contenderCmd = commands.command("contender");
        if (contenderCmd != null) contenderCmd.setExecutor(new ContenderCommand(this));

        CancelDuelCommand cancelDuelCommand = new CancelDuelCommand(this);
        commands.command("cancelduel").setExecutor(cancelDuelCommand);
        commands.command("cancelduel").setTabCompleter(cancelDuelCommand);

        var endDuelCmd = commands.command("endduel");
        if (endDuelCmd != null) endDuelCmd.setExecutor(new EndDuelCommand(duelManager));

        var startVoteCmd = commands.command("startvote");
        if (startVoteCmd != null) startVoteCmd.setExecutor(new StartVoteCommand(voteManager));

        var endVoteCmd = commands.command("endvote");
        if (endVoteCmd != null) endVoteCmd.setExecutor(new EndVoteCommand(voteManager));

        var voteCmd = commands.command("vote");
        if (voteCmd != null) voteCmd.setExecutor(new VoteCommand(this));

        var optionsCmd = commands.command("options");
        if (optionsCmd != null) optionsCmd.setExecutor(new OptionsCommand(this));
        commands.register();
    }

    private void registerRunnables() {
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new ImmediateRespawnRunnable(), 0, 20);
    }

    public me.advait.contender.hacker.HackerManager getHackerManager() { return hackerManager; }
    public void refreshHackerAttributes() { if (hackerManager != null && hackerManager.isEnabled()) hackerManager.refresh(); }
    public me.advait.contender.race.RaceManager getRaceManager() { return raceManager; }
    public me.advait.contender.minigame.MinigameManager getMinigameManager() { return minigameManager; }
    public me.advait.contender.manhunt.ManhuntManager getManhuntManager() { return manhuntManager; }
    public me.advait.contender.combo.ComboManager getComboManager() { return comboManager; }
    public DuelManager getDuelManager() { return duelManager; }
    public KitManager getKitManager() { return kitManager; }
    public MapManager getMapManager() { return mapManager; }
    public ArenaManager getArenaManager() { return arenaManager; }
    public TournamentManager getTournamentManager() { return tournamentManager; }
    public VoteManager getVoteManager() { return voteManager; }
    public LobbyManager getLobbyManager() { return lobbyManager; }
    public AllayAvatar createSpectatorAvatar(org.bukkit.entity.Player player) { return new PaperAllayAvatar(allayTransport, player); }
    public SpectatorControls getSpectatorControls() { return spectatorControls; }
    public ChatSettings getChatSettings() { return chatSettings; }
    public PvPSettings getPvpSettings() { return pvpSettings; }
    public RoleManager getRoleManager() { return roleManager; }
    public TierService getTierService() { return tierService; }
    public NameTagManager getNameTagManager() { return nameTagManager; }
    public TabManager getTabManager() { return tabManager; }
    public me.advait.contender.voice.VoiceRouting getVoiceRouting() { return voiceRouting; }
    public void refreshVoiceRouting() { if (voiceRouting != null) voiceRouting.refresh(); }
}
