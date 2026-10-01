package probe;

import me.advait.contender.Contender;
import me.advait.contender.dialog.*;
import me.advait.contender.duel.DuelSettings;
import me.advait.contender.hacker.HackSetting;
import me.advait.contender.hacker.HackerService;
import me.advait.contender.tournament.Tournament;
import me.advait.contender.tournament.TournamentEntry;
import me.advait.contender.vote.VoteService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/** Drives Contender's services directly, for scripted tests where dialogs can't be clicked. */
public class Probe extends JavaPlugin implements Listener {
    boolean logDamage;

    Contender c() { return (Contender) Bukkit.getPluginManager().getPlugin("Contender"); }

    @Override public void onEnable() { getServer().getPluginManager().registerEvents(this, this); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void damage(EntityDamageByEntityEvent e) {
        if (logDamage) getLogger().info("DMG " + e.getDamager().getName() + " -> " + e.getEntity().getName() + " cancelled=" + e.isCancelled() + " final=" + String.format("%.2f", e.getFinalDamage()));
    }

    Player p(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) throw new IllegalArgumentException("No player " + name);
        return player;
    }

    @Override public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        try {
            switch (a[0]) {
                case "kit" -> {
                    var kit = new me.advait.contender.kit.Kit(a[1]);
                    kit.setDisplayName(a[1]);
                    var items = new ItemStack[36];
                    items[0] = new ItemStack(Material.DIAMOND_SWORD);
                    items[1] = new ItemStack(Material.GOLDEN_APPLE, 4);
                    if (a.length > 2 && a[2].equals("build")) { items[2] = new ItemStack(Material.COBBLESTONE, 64); kit.setAllowBlockPlace(true); kit.setAllowBlockBreak(true); }
                    kit.setContents(items);
                    kit.setArmor(new ItemStack[]{new ItemStack(Material.IRON_BOOTS), new ItemStack(Material.IRON_LEGGINGS), new ItemStack(Material.IRON_CHESTPLATE), new ItemStack(Material.IRON_HELMET)});
                    c().getKitManager().saveKit(kit);
                    s.sendMessage("kit saved");
                }
                case "duel" -> {
                    var map = c().getMapManager().getMap(a[1]);
                    var kit = c().getKitManager().getKit(a[2]);
                    int wins = Integer.parseInt(a[3]);
                    List<List<UUID>> sides = new ArrayList<>();
                    for (int i = 4; i < a.length; i++) {
                        List<UUID> side = new ArrayList<>();
                        for (String name : a[i].split(",")) side.add(p(name).getUniqueId());
                        sides.add(side);
                    }
                    boolean ffa = sides.size() > 2;
                    var duel = c().getDuels().start(new DuelSettings(map, kit, wins, 3, ffa, 20), sides, null, null,
                            result -> getLogger().info("RESULT " + result));
                    s.sendMessage("duel " + duel.id() + " started");
                }
                case "tournament" -> {
                    List<TournamentEntry> entries = new ArrayList<>();
                    for (int i = 4; i < a.length; i++) { Player p = p(a[i]); entries.add(new TournamentEntry(p.getName(), List.of(p.getUniqueId()))); }
                    var t = new Tournament(UUID.randomUUID(), "Probe Cup", a[1], a[2], entries, false, false, Integer.parseInt(a[3]), 5, 20);
                    c().getTournaments().create(t);
                    c().getTournaments().resume();
                    s.sendMessage("tournament started with " + t.matches().size() + " matches");
                }
                case "trounds" -> {
                    var t = c().getTournaments().current();
                    for (var m : t.matches()) s.sendMessage("#" + m.number() + " r" + m.round() + " " + t.entries().get(m.first()).name() + " vs " + t.entries().get(m.second()).name() + " " + m.status() + (m.result() == null ? "" : " " + m.result()));
                    for (var st : t.standings()) s.sendMessage("  " + st.entry().name() + " pts=" + st.points() + " w=" + st.wins() + " l=" + st.losses());
                }
                case "savemap" -> c().getArenas().saveBlocks(c().getMapManager().getMap(a[1])).whenComplete((ok, fail) -> getLogger().info("savemap " + (fail == null ? "ok" : fail.toString())));
                case "endduel" -> c().getDuels().duels().forEach(d -> d.endNow());
                case "game" -> {
                    var type = c().getMinigames().type(a[1]);
                    var map = a[2].equals("-") ? null : c().getMapManager().getMap(a[2]);
                    var kit = a[3].equals("-") ? null : c().getKitManager().getKit(a[3]);
                    Map<String, String> options = new HashMap<>();
                    if (!a[4].equals("-")) for (String pair : a[4].split(",")) { String[] kv = pair.split("="); options.put(kv[0], kv[1]); }
                    Map<UUID, String> roster = new LinkedHashMap<>();
                    for (int i = 5; i < a.length; i++) roster.put(p(a[i]).getUniqueId(), a[i]);
                    var game = type.create(a[1] + " test", map, kit, roster, options);
                    c().getMinigames().select(game);
                    game.start();
                    s.sendMessage("game started: " + game.name());
                }
                case "gamestatus" -> {
                    var game = c().getMinigames().current();
                    if (game == null) { s.sendMessage("no game"); return true; }
                    s.sendMessage(game.name() + " " + game.state());
                    for (var p : game.roster()) s.sendMessage("  " + p.name + " " + p.status + " score=" + p.score + " value=" + p.value + " place=" + p.place);
                    var layout = c().getTabManager().layout(me.advait.contender.tab.BracketLayout.View.following());
                    if (layout != null) for (var row : layout.rows()) { String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(row.text()); if (!text.isBlank()) s.sendMessage("  | " + text); }
                }
                case "gameend" -> c().getMinigames().current().finish();
                case "gamecancel" -> { if (c().getMinigames().current() != null) c().getMinigames().current().cancel("probe"); }
                case "course" -> {
                    var race = (me.advait.contender.minigame.race.RaceType) c().getMinigames().type("mace_race");
                    var world = Bukkit.getWorld(a[2]);
                    var existing = race.courses().get(me.advait.contender.map.MapManager.idFor(a[1]));
                    if (existing != null) race.courses().delete(existing.id());
                    var course = race.courses().create(a[1], world);
                    course.setStart(new org.bukkit.Location(world, Double.parseDouble(a[3]), Double.parseDouble(a[4]), Double.parseDouble(a[5])));
                    for (int i = 6; i + 2 < a.length; i += 3) course.add(new org.bukkit.Location(world, Double.parseDouble(a[i]), Double.parseDouble(a[i + 1]), Double.parseDouble(a[i + 2])), org.bukkit.entity.EntityType.ZOMBIE);
                    race.courses().save();
                    s.sendMessage("course " + course.id() + " with " + course.size() + " checkpoints, problem=" + course.problem());
                }
                case "editor" -> {
                    var race = (me.advait.contender.minigame.race.RaceType) c().getMinigames().type("mace_race");
                    race.editor().start(p(a[1]), race.courses().get(a[2]));
                }
                case "import" -> {
                    var race = (me.advait.contender.minigame.race.RaceType) c().getMinigames().type("mace_race");
                    s.sendMessage("legacy: " + race.courses().legacyCourses());
                    race.courses().importLegacy(a[1]).whenComplete((course, failure) -> Bukkit.getScheduler().runTask(c(), () ->
                            Bukkit.getLogger().info("IMPORT RESULT " + (failure != null ? "failed: " + failure : course.id() + " " + course.size() + " " + course.problem()))));
                }
                case "manhuntprep" -> {
                    var mh = (me.advait.contender.minigame.manhunt.ManhuntType) c().getMinigames().type("manhunt");
                    mh.world().prepare(() -> true).whenComplete((ok, failure) -> Bukkit.getScheduler().runTask(c(), () ->
                            Bukkit.getLogger().info("MANHUNT PREP " + (failure == null ? "ok " + mh.world().name() + " pillars=" + mh.world().pillarCount() : "failed " + failure))));
                }
                case "manhuntstate" -> {
                    var mh = (me.advait.contender.minigame.manhunt.ManhuntType) c().getMinigames().type("manhunt");
                    s.sendMessage("state=" + mh.world().state() + " name=" + mh.world().name() + " frozen=" + mh.world().frozen());
                }
                case "killdragon" -> {
                    var mh = (me.advait.contender.minigame.manhunt.ManhuntType) c().getMinigames().type("manhunt");
                    for (var d : mh.world().world().getEntitiesByClass(org.bukkit.entity.EnderDragon.class)) d.damage(1000, p(a[1]));
                }
                case "boardplace" -> c().getBoard().place(p(a[1]));
                case "boardremove" -> c().getBoard().remove();
                case "theme" -> { c().getThemes().select(a[1]); s.sendMessage("theme " + c().getThemes().current().name()); }
                case "displays" -> {
                    int text = 0, block = 0, item = 0;
                    for (var w : Bukkit.getWorlds()) for (var e : w.getEntities()) {
                        if (e instanceof org.bukkit.entity.TextDisplay) text++;
                        else if (e instanceof org.bukkit.entity.BlockDisplay) block++;
                        else if (e instanceof org.bukkit.entity.ItemDisplay) item++;
                    }
                    s.sendMessage("text=" + text + " block=" + block + " item=" + item);
                }
                case "withdraw" -> c().getMinigames().current().withdraw(Bukkit.getOfflinePlayer(a[1]).getUniqueId());
                case "packall" -> {
                    var allay = p(a[1]).getWorld().createEntity(p(a[1]).getLocation(), org.bukkit.entity.Allay.class);
                    allay.setCustomNameVisible(true);
                    allay.customName(net.kyori.adventure.text.Component.text("Test"));
                    Object handle = allay.getClass().getMethod("getHandle").invoke(allay);
                    Object data = handle.getClass().getMethod("getEntityData").invoke(handle);
                    for (var method : data.getClass().getMethods()) if (method.getName().startsWith("pack") || method.getName().contains("NonDefault")) {
                        if (method.getParameterCount() == 0) s.sendMessage(method.getName() + " -> " + method.invoke(data));
                    }
                }
                case "falldist" -> p(a[1]).setFallDistance(Float.parseFloat(a[2]));
                case "editorstop" -> ((me.advait.contender.minigame.race.RaceType) c().getMinigames().type("mace_race")).editor().stop(p(a[1]));
                case "coursedump" -> {
                    var race = (me.advait.contender.minigame.race.RaceType) c().getMinigames().type("mace_race");
                    var course = race.courses().get(a[1]);
                    s.sendMessage(course.id() + " start=" + course.start() + " problem=" + course.problem());
                    for (int i = 0; i < course.size(); i++) s.sendMessage("  " + course.label(i) + " " + course.checkpoints().get(i));
                }
                case "mobs" -> {
                    for (var e : p(a[1]).getNearbyEntities(Double.parseDouble(a[2]), 64, Double.parseDouble(a[2]))) {
                        if (e instanceof org.bukkit.entity.LivingEntity le && !(e instanceof Player)) s.sendMessage(e.getUniqueId() + " " + e.getType() + " " + (le.customName() == null ? "" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(le.customName())) + " " + String.format("%.1f,%.1f,%.1f", e.getX(), e.getY(), e.getZ()));
                    }
                }
                case "give" -> p(a[1]).getInventory().setItem(Integer.parseInt(a[2]), new ItemStack(Material.valueOf(a[3])));
                case "pause" -> c().getTournaments().pause();
                case "resume" -> c().getTournaments().resume();
                case "watch" -> {
                    var duel = c().getDuels().duels().stream().filter(d -> d.id() == Integer.parseInt(a[2])).findFirst().orElseThrow();
                    c().getSpectate().watch(p(a[1]), duel);
                }
                case "watchmatch" -> c().getSpectate().watch(p(a[1]), c().getTournaments().playing().values().iterator().next());
                case "watchgame" -> c().getSpectate().watch(p(a[1]), c().getMinigames().current());
                case "unwatch" -> c().getSpectate().stop(p(a[1]).getUniqueId(), true);
                case "dmglog" -> logDamage = a[1].equals("on");
                case "dialog" -> {
                    Player target = p(a[1]);
                    switch (a[2]) {
                        case "tournament" -> new TournamentDialogs(c()).open(target);
                        case "formats" -> new TournamentDialogs(c()).formats(target);
                        case "tools" -> new TournamentDialogs(c()).tools(target);
                        case "manhuntsetup" -> c().getMinigames().type("manhunt").openSetup(target);
                        case "manhuntcreate" -> c().getMinigames().type("manhunt").openCreate(target);
                        case "combocreate" -> c().getMinigames().type("combo").openCreate(target);
                        case "racesetup" -> c().getMinigames().type("mace_race").openSetup(target);
                        case "racecreate" -> c().getMinigames().type("mace_race").openCreate(target);
                        case "minigames" -> new TournamentDialogs(c()).minigameTools(target);
                        case "duel" -> new DuelDialogs(c()).open(target);
                        case "arena" -> new ArenaDialogs(c()).open(target);
                        case "spectate" -> new SpectateDialogs(c()).open(target);
                        case "vote" -> new VoteDialogs(c()).open(target);
                        case "startvote" -> new VoteDialogs(c()).start(target);
                        case "hacks" -> new HackerDialogs(c()).open(target);
                        case "hackers" -> new HackerAdminDialogs(c()).open(target);
                        case "sabotage" -> new SabotageDialogs(c()).open(target);
                        case "sabotagesettings" -> new SabotageDialogs(c()).settings(target, x -> {});
                        case "options" -> new SettingsDialogs(c()).open(target);
                        case "theme" -> new SettingsDialogs(c()).theme(target);
                        case "bracket" -> new BracketDialogs(c()).open(target);
                        case "kits" -> new KitDialogs(c()).open(target, x -> {});
                        case "roles" -> new RoleDialogs(c()).open(target);
                        case "game" -> new MinigameDialogs(c()).control(target, c().getMinigames().current());
                        default -> s.sendMessage("unknown dialog");
                    }
                    s.sendMessage("dialog shown");
                }
                case "vote" -> {
                    c().getVotes().start(new VoteService.Options(Integer.parseInt(a[1]), a.length > 2 && a[2].equals("live"), true, false));
                }
                case "votefor" -> {
                    var session = c().getVotes().session();
                    c().getVotes().vote(p(a[1]), session.byNumber(Integer.parseInt(a[2])));
                }
                case "hackers" -> {
                    List<Player> players = new ArrayList<>();
                    for (int i = 1; i < a.length; i++) players.add(p(a[i]));
                    c().getHackers().pick(players);
                }
                case "hackmode" -> c().getHackers().setMode(HackerService.Mode.valueOf(a[1].toUpperCase()));
                case "sethack" -> {
                    Player target = p(a[1]);
                    var profile = c().getHackers().profile(target.getUniqueId());
                    c().getHackers().setOwn(target, profile.own().with(HackSetting.valueOf(a[2].toUpperCase()), Double.parseDouble(a[3])));
                }
                case "plan" -> c().getHackers().setPlan(null, c().getHackers().sharedPlan().with(HackSetting.valueOf(a[1].toUpperCase()), Double.parseDouble(a[2])));
                case "attr" -> {
                    Player target = p(a[1]);
                    for (var attribute : List.of(org.bukkit.attribute.Attribute.ENTITY_INTERACTION_RANGE, org.bukkit.attribute.Attribute.MOVEMENT_SPEED,
                            org.bukkit.attribute.Attribute.MAX_HEALTH, org.bukkit.attribute.Attribute.SCALE, org.bukkit.attribute.Attribute.ATTACK_DAMAGE, org.bukkit.attribute.Attribute.GRAVITY)) {
                        var instance = target.getAttribute(attribute);
                        if (instance != null) s.sendMessage(attribute.getKey().getKey() + "=" + String.format("%.3f", instance.getValue()));
                    }
                }
                case "sabotage" -> {
                    var sabotage = c().getSabotage().byId(a[1]);
                    if (a.length > 2) c().getSabotage().trigger(p(a[2]), sabotage);
                    else c().getSabotage().force(sabotage, null);
                }
                case "sabotageconfig" -> c().getSabotage().configure(Boolean.parseBoolean(a[1]), true, Integer.parseInt(a[2]), Integer.parseInt(a[3]), 0, 5, false);
                case "registry" -> {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        var claim = c().getRegistry().claim(player.getUniqueId());
                        s.sendMessage(player.getName() + ": " + (claim == null ? "free" : claim.activity().displayName() + " " + claim.involvement())
                                + " snapshot=" + c().getSnapshots().has(player.getUniqueId()) + " mode=" + player.getGameMode() + " world=" + player.getWorld().getName());
                    }
                }
                default -> s.sendMessage("unknown");
            }
        } catch (Exception e) {
            s.sendMessage("ERR " + e);
            getLogger().log(java.util.logging.Level.WARNING, "probe", e);
        }
        return true;
    }
}
