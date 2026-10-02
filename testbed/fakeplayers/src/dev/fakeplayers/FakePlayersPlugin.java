package dev.fakeplayers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Server-side fake players for testing on a single Paper server. They are real players to the server and to
 * plugins (they join, count as online, take damage, run commands); only the network connection is fake.
 * Based on the UHCR FakePlayers tool, without its MultiPaper cluster features.
 *
 * <pre>
 * Spawning and removing
 *   /bots spawn &lt;count&gt; [radius]       Bot_1, Bot_2, ... joining like real players (so a lobby plugin places
 *                                       them); with a radius, spread out around you and kept there instead
 *   /bots spawn &lt;name...&gt;              bots with these names, where you stand
 *   /bots spawnn &lt;count&gt; [prefix]       prefix1..prefixN where you stand
 *   /bots remove [all|name...]          disconnect bots
 *   /bots list
 * Moving around
 *   /bots wander &lt;on|off&gt; [name...]    walk around randomly near where they are
 *   /bots fight &lt;on|off&gt; [name...]     chase and punch the nearest player
 *   /bots tphere                        teleport every bot to you
 *   /bots gather &lt;radius&gt; [x z]        spread every bot over random surface spots within radius
 * Acting as one bot (name, or "all" for every bot)
 *   /bots cmd|sudo &lt;name|all&gt; &lt;command&gt;   run a command, without the slash
 *   /bots chat &lt;name|all&gt; &lt;message&gt;
 *   /bots tp &lt;name&gt; &lt;world&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; [yaw] [pitch]
 *   /bots attack &lt;name&gt; &lt;player|nearest|uuid&gt;
 *   /bots use|swing &lt;name&gt;             right-click with the held item, or swing
 *   /bots slot &lt;name&gt; &lt;0-8&gt;     /bots look &lt;name&gt; &lt;yaw&gt; &lt;pitch&gt;
 *   /bots walk &lt;name&gt; &lt;ticks&gt;  /bots vel &lt;name&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt;     /bots info &lt;name&gt;
 * Diagnostics
 *   /bots chatlog &lt;off|chat|all&gt;       record what bots see to plugins/FakePlayers/chat.log
 *   /bots verify &lt;on|off&gt;              encode every packet like a real connection, to catch broken ones
 *   /bots sounds &lt;on|off&gt;              log every sound the bots hear
 *   /bots sys                           memory, load and CPUs
 * </pre>
 * Ticks slower than 35 ms are appended to plugins/FakePlayers/slow-ticks.log.
 */
public class FakePlayersPlugin extends JavaPlugin implements Listener {
    static FakePlayersPlugin instance;
    static final String PREFIX = "Bot_";
    /** Spread big batches over ticks so a mass spawn doesn't freeze the server. */
    static final int SPAWNS_PER_TICK = 2;
    final Map<String, Bot> bots = new ConcurrentHashMap<>();
    volatile boolean fightAll;
    volatile boolean wanderAll;
    boolean disabling;

    @Override public void onEnable() {
        instance = this;
        getServer().getPluginManager().registerEvents(this, this);
        try {
            getDataFolder().mkdirs();
            getServer().getPluginManager().registerEvents(new SlowTickLogger(this), this);
        } catch (java.io.IOException e) {
            getLogger().warning("Slow tick logging disabled: " + e);
        }
        Bukkit.getScheduler().runTaskTimer(this, () -> ChatCapture.flush(false), 20L, 20L);
    }

    @Override public void onDisable() {
        disabling = true;
        new ArrayList<>(bots.values()).forEach(bot -> bot.remove("Removed"));
        bots.clear();
        ChatCapture.close();
    }

    private Bot bot(CommandSender sender, String name) {
        Bot bot = bots.get(name);
        if (bot == null) sender.sendMessage("No bot named " + name);
        return bot;
    }

    /** One bot, or every bot for "all" (or "each", as in UHCR). */
    private List<Bot> targets(CommandSender sender, String name) {
        if (name.equalsIgnoreCase("all") || name.equalsIgnoreCase("each")) return new ArrayList<>(bots.values());
        Bot bot = bot(sender, name);
        return bot == null ? List.of() : List.of(bot);
    }

    private static boolean on(String value) { return value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true"); }

    private static Location origin(CommandSender sender) {
        return sender instanceof Player p ? p.getLocation() : Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return false;
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "spawn" -> {
                    if (args.length < 2) return false;
                    if (args[1].matches("\\d+")) {
                        // Without a radius they join like real players, so the server's join handling (a lobby
                        // teleport, say) places them. With one, they're spread out around you and kept there.
                        int radius = args.length > 2 ? Integer.parseInt(args[2]) : 0;
                        spawnSpread(sender, Integer.parseInt(args[1]), radius, origin(sender));
                    } else {
                        for (int i = 1; i < args.length; i++) spawn(sender, args[i], origin(sender), 8);
                    }
                }
                case "spawnn" -> {
                    int count = Integer.parseInt(args[1]);
                    String prefix = args.length > 2 ? args[2] : "Bot";
                    for (int i = 1; i <= count; i++) spawn(sender, prefix + i, origin(sender), 8);
                }
                case "remove", "clear" -> {
                    if (args.length < 2 || args[1].equalsIgnoreCase("all")) {
                        new ArrayList<>(bots.values()).forEach(bot -> bot.remove("Removed"));
                        bots.clear();
                    } else for (int i = 1; i < args.length; i++) {
                        Bot bot = bots.remove(args[i]);
                        if (bot != null) bot.remove("Removed");
                    }
                    sender.sendMessage("Removed");
                }
                case "list" -> sender.sendMessage("Bots (" + bots.size() + "): " + String.join(", ", new TreeSet<>(bots.keySet()))
                        + " · wander " + (wanderAll ? "on" : "off") + " · fight " + (fightAll ? "on" : "off")
                        + " · packets encoded=" + FakeConnection.encoded.get() + " failed=" + FakeConnection.failures.get());
                case "fight" -> {
                    boolean on = args.length < 2 ? !fightAll : on(args[1]);
                    if (args.length <= 2) { fightAll = on; if (!on) bots.values().forEach(bot -> bot.fight = false); }
                    else for (int i = 2; i < args.length; i++) { Bot bot = bot(sender, args[i]); if (bot != null) bot.fight = on; }
                    sender.sendMessage("Fight " + (on ? "on" : "off"));
                }
                case "wander" -> {
                    boolean on = args.length < 2 ? !wanderAll : on(args[1]);
                    if (args.length <= 2) { wanderAll = on; if (!on) bots.values().forEach(bot -> bot.wander = false); }
                    else for (int i = 2; i < args.length; i++) { Bot bot = bot(sender, args[i]); if (bot != null) bot.wander = on; }
                    sender.sendMessage("Wander " + (on ? "on" : "off"));
                }
                case "tphere" -> {
                    if (!(sender instanceof Player p)) return false;
                    bots.values().forEach(bot -> bot.getBukkitEntity().teleport(p.getLocation()));
                }
                case "gather" -> {
                    if (args.length < 2) return false;
                    int radius = Integer.parseInt(args[1]);
                    Location origin = origin(sender);
                    double x = args.length >= 4 ? Double.parseDouble(args[2]) : origin.getX();
                    double z = args.length >= 4 ? Double.parseDouble(args[3]) : origin.getZ();
                    gather(radius, x, z);
                    sender.sendMessage("Gathering " + bots.size() + " bots within " + radius + " blocks");
                }
                case "cmd", "sudo" -> {
                    if (args.length < 3) return false;
                    String cmd = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                    for (Bot bot : targets(sender, args[1])) {
                        boolean ok = bot.getBukkitEntity().performCommand(cmd);
                        sender.sendMessage(bot.getGameProfile().name() + " ran /" + cmd + " -> " + ok);
                    }
                }
                case "chat" -> {
                    if (args.length < 3) return false;
                    String message = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                    for (Bot bot : targets(sender, args[1])) bot.getBukkitEntity().chat(message);
                }
                case "tp" -> {
                    Bot bot = bot(sender, args[1]);
                    World world = Bukkit.getWorld(args[2]);
                    if (bot != null && world != null) {
                        Location to = new Location(world, Double.parseDouble(args[3]), Double.parseDouble(args[4]), Double.parseDouble(args[5]),
                                args.length > 6 ? Float.parseFloat(args[6]) : 0, args.length > 7 ? Float.parseFloat(args[7]) : 0);
                        sender.sendMessage("tp " + bot.getBukkitEntity().teleport(to));
                    }
                }
                case "attack" -> {
                    Bot bot = bot(sender, args[1]);
                    if (bot == null) return true;
                    Entity target = null;
                    Player self = bot.getBukkitEntity();
                    if (args[2].equalsIgnoreCase("nearest")) {
                        double best = Double.MAX_VALUE;
                        for (Entity entity : self.getNearbyEntities(6, 6, 6)) {
                            double d = entity.getLocation().distanceSquared(self.getLocation());
                            if (d < best && !(entity instanceof org.bukkit.entity.Display)) { best = d; target = entity; }
                        }
                    } else if (args[2].contains("-")) target = Bukkit.getEntity(UUID.fromString(args[2]));
                    else target = Bukkit.getPlayerExact(args[2]);
                    if (target == null) { sender.sendMessage("No target"); return true; }
                    Vector to = target.getLocation().toVector().subtract(self.getEyeLocation().toVector());
                    Location look = self.getLocation().setDirection(to);
                    bot.setYRot(look.getYaw()); bot.setYHeadRot(look.getYaw()); bot.setXRot(look.getPitch());
                    bot.punch(((CraftEntity) target).getHandle());
                    sender.sendMessage(args[1] + " attacked " + target.getName());
                }
                case "use" -> { Bot bot = bot(sender, args[1]); if (bot != null) bot.useItem(); }
                case "swing" -> { Bot bot = bot(sender, args[1]); if (bot != null) bot.swing(); }
                case "slot" -> { Bot bot = bot(sender, args[1]); if (bot != null) bot.getBukkitEntity().getInventory().setHeldItemSlot(Integer.parseInt(args[2])); }
                case "look" -> {
                    Bot bot = bot(sender, args[1]);
                    if (bot != null) { bot.setYRot(Float.parseFloat(args[2])); bot.setYHeadRot(Float.parseFloat(args[2])); bot.setXRot(Float.parseFloat(args[3])); }
                }
                case "walk" -> { Bot bot = bot(sender, args[1]); if (bot != null) bot.walkTicks = Integer.parseInt(args[2]); }
                case "vel" -> {
                    Bot bot = bot(sender, args[1]);
                    if (bot != null) bot.getBukkitEntity().setVelocity(new Vector(Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4])));
                }
                case "info" -> {
                    Bot bot = bot(sender, args[1]);
                    if (bot != null) {
                        Player p = bot.getBukkitEntity();
                        Location l = p.getLocation();
                        sender.sendMessage(String.format(Locale.ROOT, "%s world=%s pos=%.2f,%.2f,%.2f mode=%s health=%.1f/%.1f food=%d dead=%s flying=%s fight=%s wander=%s inv=%s",
                                p.getName(), l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), p.getGameMode(), p.getHealth(),
                                p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(), p.getFoodLevel(), p.isDead(), p.isFlying(),
                                bot.fight || fightAll, bot.wander || wanderAll,
                                Arrays.stream(p.getInventory().getContents()).filter(Objects::nonNull).map(i -> i.getType() + "x" + i.getAmount()).toList()));
                    }
                }
                case "chatlog" -> {
                    ChatCapture.Mode mode = ChatCapture.Mode.valueOf(args.length < 2 ? "CHAT" : args[1].toUpperCase(Locale.ROOT));
                    ChatCapture.setMode(this, mode);
                    sender.sendMessage("Chat capture " + mode.name().toLowerCase(Locale.ROOT) + " (plugins/FakePlayers/chat.log)");
                }
                case "sounds" -> {
                    Bot.logSounds = args.length < 2 ? !Bot.logSounds : on(args[1]);
                    sender.sendMessage("Sound logging " + (Bot.logSounds ? "on (server log)" : "off"));
                }
                case "verify" -> {
                    FakeConnection.verifyPackets = args.length < 2 ? !FakeConnection.verifyPackets : on(args[1]);
                    sender.sendMessage("Packet checks " + (FakeConnection.verifyPackets ? "on" : "off"));
                }
                case "sys" -> {
                    Runtime runtime = Runtime.getRuntime();
                    String load;
                    try { load = java.nio.file.Files.readString(java.nio.file.Path.of("/proc/loadavg")).trim(); }
                    catch (java.io.IOException | RuntimeException e) { load = "unavailable"; }
                    sender.sendMessage("load " + load + " · cpus " + runtime.availableProcessors()
                            + " · heap " + (runtime.totalMemory() - runtime.freeMemory()) / 1048576 + "/" + runtime.maxMemory() / 1048576 + " MB");
                }
                default -> { return false; }
            }
        } catch (RuntimeException failure) {
            sender.sendMessage("Error: " + failure);
            getLogger().log(java.util.logging.Level.WARNING, "bots command failed", failure);
        }
        return true;
    }

    private void spawn(CommandSender sender, String name, Location at, int radius) {
        if (bots.containsKey(name)) { sender.sendMessage(name + " is already online"); return; }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            // A bot that was just removed stays online until the next tick; spawn the new one once it's gone.
            if (((CraftEntity) online).getHandle() instanceof Bot leaving && leaving.removed) {
                Bukkit.getScheduler().runTaskLater(this, () -> spawn(sender, name, at, radius), 2L);
            } else sender.sendMessage(name + " is already online");
            return;
        }
        Bot bot = Bot.spawn(this, name, at, radius);
        if (bot != null) { bots.put(name, bot); sender.sendMessage("Spawned " + name); }
        else sender.sendMessage("Could not spawn " + name);
    }

    /** Bot_1, Bot_2, ... (skipping names already online) on random surface spots around the center. */
    private void spawnSpread(CommandSender sender, int count, int radius, Location center) {
        Set<String> taken = new HashSet<>();
        Bukkit.getOnlinePlayers().forEach(p -> taken.add(p.getName()));
        List<String> names = new ArrayList<>();
        for (int i = 1; names.size() < count; i++) if (!taken.contains(PREFIX + i)) names.add(PREFIX + i);
        World world = center.getWorld();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                int x = center.getBlockX() + (radius > 0 ? random.nextInt(-radius, radius + 1) : 0);
                int z = center.getBlockZ() + (radius > 0 ? random.nextInt(-radius, radius + 1) : 0);
                world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> {
                    if (!isEnabled() || bots.containsKey(name)) return;
                    Location at = new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1, z + 0.5, random.nextFloat() * 360 - 180, 0);
                    Bot bot = Bot.spawn(this, name, at, Math.max(8, radius));
                    if (bot == null) { getLogger().warning("Could not spawn " + name); return; }
                    bots.put(name, bot);
                    // Servers that send joining players to a lobby spawn would stack every bot there; put it back.
                    if (radius > 0) Bukkit.getScheduler().runTaskLater(this, () -> {
                        Player player = bot.getBukkitEntity();
                        if (!bot.hasDisconnected() && player.getLocation().distanceSquared(at) > 4) player.teleport(at);
                    }, 5L);
                });
            }, i / SPAWNS_PER_TICK);
        }
        sender.sendMessage(radius > 0 ? "Spawning " + names.size() + " bots within " + radius + " blocks" : "Spawning " + names.size() + " bots");
    }

    /** Moves every bot to a random surface spot within radius of x z, in its own world, spread over ticks. */
    private void gather(int radius, double cx, double cz) {
        List<Bot> all = new ArrayList<>(bots.values());
        for (int i = 0; i < all.size(); i++) {
            Bot bot = all.get(i);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (bot.hasDisconnected()) return;
                World world = bot.getBukkitEntity().getWorld();
                ThreadLocalRandom random = ThreadLocalRandom.current();
                int x = (int) Math.floor(cx) + random.nextInt(-radius, radius + 1);
                int z = (int) Math.floor(cz) + random.nextInt(-radius, radius + 1);
                world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> {
                    if (bot.hasDisconnected()) return;
                    bot.getBukkitEntity().teleport(new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1, z + 0.5));
                });
            }, i / SPAWNS_PER_TICK);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("spawn", "spawnn", "remove", "list", "wander", "fight", "tphere", "gather", "cmd", "sudo", "chat",
                "tp", "attack", "use", "swing", "slot", "look", "walk", "vel", "info", "chatlog", "sounds", "verify", "sys");
        if (args.length == 2 && Set.of("wander", "fight", "verify", "sounds").contains(args[0].toLowerCase(Locale.ROOT))) return List.of("on", "off");
        if (args.length == 2 && args[0].equalsIgnoreCase("chatlog")) return List.of("off", "chat", "all");
        List<String> names = new ArrayList<>(bots.keySet());
        if (args.length == 2 && Set.of("cmd", "sudo", "chat", "remove").contains(args[0].toLowerCase(Locale.ROOT))) names.add("all");
        return names;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Bot bot = bots.get(event.getPlayer().getName());
        if (bot != null && bot.getBukkitEntity() == event.getPlayer()) bots.remove(event.getPlayer().getName());
    }
}
