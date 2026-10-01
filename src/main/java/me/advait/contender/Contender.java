package me.advait.contender;

import me.advait.contender.activity.ActivityRegistry;
import me.advait.contender.activity.SnapshotStore;
import me.advait.contender.arena.ArenaListener;
import me.advait.contender.arena.ArenaService;
import me.advait.contender.chat.ChatListener;
import me.advait.contender.chat.ChatManager;
import me.advait.contender.chat.ChatSettings;
import me.advait.contender.command.Commands;
import me.advait.contender.core.Module;
import me.advait.contender.display.Celebrations;
import me.advait.contender.display.StageBoard;
import me.advait.contender.display.Themes;
import me.advait.contender.duel.DeathEffect;
import me.advait.contender.duel.DuelService;
import me.advait.contender.gui.GUIListener;
import me.advait.contender.hacker.HackerService;
import me.advait.contender.interview.InterviewService;
import me.advait.contender.kit.KitManager;
import me.advait.contender.lobby.LobbyListener;
import me.advait.contender.lobby.LobbyService;
import me.advait.contender.map.MapManager;
import me.advait.contender.minigame.MinigameService;
import me.advait.contender.nametag.NameTagManager;
import me.advait.contender.pvp.PvPListener;
import me.advait.contender.pvp.PvPSettings;
import me.advait.contender.role.RoleManager;
import me.advait.contender.sabotage.SabotageService;
import me.advait.contender.spectate.SpectateService;
import me.advait.contender.stage.StageService;
import me.advait.contender.tab.TabManager;
import me.advait.contender.tier.TierService;
import me.advait.contender.tournament.TournamentService;
import me.advait.contender.voice.VoiceService;
import me.advait.contender.vote.VoteService;
import me.advait.contender.world.ManagedWorlds;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Contender: duels, round robins, minigames and the "secret hacker" show tools.
 * Services are created first, then modules are enabled in order and disabled in reverse.
 */
public final class Contender extends JavaPlugin {
    private final List<Module> modules = new ArrayList<>();

    private RoleManager roles;
    private TierService tiers;
    private KitManager kits;
    private MapManager maps;
    private ManagedWorlds managedWorlds;
    private LobbyService lobby;
    private SnapshotStore snapshots;
    private ChatSettings chatSettings;
    private PvPSettings pvpSettings;
    private Themes themes;
    private Celebrations celebrations;
    private DeathEffect deathEffect;

    private ActivityRegistry registry;
    private StageService stages;
    private NameTagManager nameTags;
    private ArenaService arenas;
    private DuelService duels;
    private SpectateService spectate;
    private TournamentService tournaments;
    private MinigameService minigames;
    private VoteService votes;
    private HackerService hackers;
    private SabotageService sabotage;
    private InterviewService interviews;
    private TabManager tab;
    private StageBoard board;
    private VoiceService voice;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        roles = new RoleManager(this);
        tiers = new TierService(this);
        lobby = new LobbyService(this);
        kits = new KitManager(this);
        maps = new MapManager(this);
        managedWorlds = new ManagedWorlds(this);
        snapshots = new SnapshotStore(this);
        chatSettings = new ChatSettings(this);
        pvpSettings = new PvPSettings(this);
        themes = new Themes(this);
        celebrations = new Celebrations(this);
        deathEffect = new DeathEffect(this);

        registry = add(new ActivityRegistry(this));
        stages = add(new StageService(this));
        nameTags = add(new NameTagManager(this));
        arenas = add(new ArenaService(this, maps));
        duels = add(new DuelService(this));
        spectate = add(new SpectateService(this));
        hackers = add(new HackerService(this));
        sabotage = add(new SabotageService(this));
        tournaments = add(new TournamentService(this));
        minigames = add(new MinigameService(this));
        votes = add(new VoteService(this));
        interviews = add(new InterviewService(this));
        tab = add(new TabManager(this));
        board = add(new StageBoard(this));
        voice = add(new VoiceService(this));

        loadWorlds();
        for (Listener listener : List.of(managedWorlds, celebrations, deathEffect, new LobbyListener(this, lobby),
                new ArenaListener(this, arenas), new ChatListener(this), new ChatManager(this), new PvPListener(this), new GUIListener(this), new me.advait.contender.display.QuietToasts(this),
                new me.advait.contender.command.QuickActions(this))) {
            getServer().getPluginManager().registerEvents(listener, this);
        }
        for (Module module : modules) {
            try { module.enable(); }
            catch (RuntimeException | LinkageError failure) {
                getLogger().log(Level.SEVERE, "Could not start " + module.getClass().getSimpleName() + ". That feature is unavailable.", failure);
            }
        }
        new Commands(this).register();
        for (var player : getServer().getOnlinePlayers()) lobby.applyRoleMode(player);
        getLogger().info("Contender " + getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        for (int i = modules.size() - 1; i >= 0; i--) {
            try { modules.get(i).disable(); }
            catch (RuntimeException | LinkageError failure) {
                getLogger().log(Level.SEVERE, "Could not stop " + modules.get(i).getClass().getSimpleName() + " cleanly.", failure);
            }
        }
        modules.clear();
        if (tiers != null) tiers.close();
    }

    private <T extends Module> T add(T module) {
        modules.add(module);
        return module;
    }

    /** Loads the lobby and every source map's world, so templates and spawns resolve. */
    public void loadWorlds() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        names.add(lobby.worldName());
        maps.getMaps().forEach(map -> names.add(map.getWorldName()));
        for (String name : names) {
            if (getServer().getWorld(name) != null) continue;
            try { managedWorlds.loadExisting(name); }
            catch (RuntimeException failure) { getLogger().warning("Could not load world '" + name + "': " + failure.getMessage()); }
        }
    }

    public RoleManager getRoleManager() { return roles; }
    public TierService getTierService() { return tiers; }
    public KitManager getKitManager() { return kits; }
    public MapManager getMapManager() { return maps; }
    public ManagedWorlds getManagedWorlds() { return managedWorlds; }
    public LobbyService getLobby() { return lobby; }
    public SnapshotStore getSnapshots() { return snapshots; }
    public ChatSettings getChatSettings() { return chatSettings; }
    public PvPSettings getPvpSettings() { return pvpSettings; }
    public Themes getThemes() { return themes; }
    public Celebrations getCelebrations() { return celebrations; }
    public DeathEffect getDeathEffect() { return deathEffect; }
    public ActivityRegistry getRegistry() { return registry; }
    public StageService getStages() { return stages; }
    public NameTagManager getNameTagManager() { return nameTags; }
    public ArenaService getArenas() { return arenas; }
    public DuelService getDuels() { return duels; }
    public SpectateService getSpectate() { return spectate; }
    public TournamentService getTournaments() { return tournaments; }
    public MinigameService getMinigames() { return minigames; }
    public VoteService getVotes() { return votes; }
    public HackerService getHackers() { return hackers; }
    public SabotageService getSabotage() { return sabotage; }
    public InterviewService getInterviews() { return interviews; }
    public TabManager getTabManager() { return tab; }
    public StageBoard getBoard() { return board; }
    public VoiceService getVoice() { return voice; }
}
