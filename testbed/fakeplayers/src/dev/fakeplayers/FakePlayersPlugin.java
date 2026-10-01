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

/**
 * Test bots for a single Paper server.
 * /bots spawn <name...>           spawn named bots at the sender (or world spawn)
 * /bots spawnn <count> [prefix]   spawn prefix1..prefixN
 * /bots remove [name|all]         disconnect bots
 * /bots list
 * /bots fight <on|off> [name...]  chase and punch the nearest player
 * /bots cmd <name> <command...>   run a command as the bot
 * /bots chat <name> <message...>
 * /bots tp <name> <world> <x> <y> <z> [yaw] [pitch]
 * /bots attack <name> <target-player|nearest|uuid>
 * /bots use <name>                right-click with the held item
 * /bots slot <name> <0-8>
 * /bots look <name> <yaw> <pitch>
 * /bots walk <name> <ticks>
 * /bots vel <name> <x> <y> <z>
 * /bots info <name>
 */
public class FakePlayersPlugin extends JavaPlugin implements Listener {
    static FakePlayersPlugin instance;
    final Map<String, Bot> bots = new ConcurrentHashMap<>();
    volatile boolean fightAll;
    boolean disabling;

    @Override public void onEnable() {
        instance = this;
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override public void onDisable() {
        disabling = true;
        new ArrayList<>(bots.values()).forEach(bot -> bot.remove("Removed"));
        bots.clear();
    }

    private Bot bot(CommandSender sender, String name) {
        Bot bot = bots.get(name);
        if (bot == null) sender.sendMessage("No bot named " + name);
        return bot;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return false;
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "spawn" -> {
                    Location at = sender instanceof Player p ? p.getLocation() : Bukkit.getWorlds().getFirst().getSpawnLocation();
                    for (int i = 1; i < args.length; i++) spawn(sender, args[i], at);
                }
                case "spawnn" -> {
                    int count = Integer.parseInt(args[1]);
                    String prefix = args.length > 2 ? args[2] : "Bot";
                    Location at = sender instanceof Player p ? p.getLocation() : Bukkit.getWorlds().getFirst().getSpawnLocation();
                    for (int i = 1; i <= count; i++) spawn(sender, prefix + i, at);
                }
                case "remove" -> {
                    if (args.length < 2 || args[1].equals("all")) {
                        new ArrayList<>(bots.values()).forEach(bot -> bot.remove("Removed"));
                        bots.clear();
                    } else for (int i = 1; i < args.length; i++) {
                        Bot bot = bots.remove(args[i]);
                        if (bot != null) bot.remove("Removed");
                    }
                    sender.sendMessage("Removed");
                }
                case "list" -> sender.sendMessage("Bots (" + bots.size() + "): " + String.join(", ", new TreeSet<>(bots.keySet()))
                        + " · packets encoded=" + FakeConnection.encoded.get() + " failed=" + FakeConnection.failures.get());
                case "fight" -> {
                    boolean on = args[1].equalsIgnoreCase("on");
                    if (args.length == 2) fightAll = on;
                    else for (int i = 2; i < args.length; i++) { Bot bot = bot(sender, args[i]); if (bot != null) bot.fight = on; }
                    if (!on && args.length == 2) bots.values().forEach(bot -> bot.fight = false);
                    sender.sendMessage("Fight " + (on ? "on" : "off"));
                }
                case "cmd" -> {
                    Bot bot = bot(sender, args[1]);
                    if (bot != null) {
                        String cmd = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                        boolean ok = bot.getBukkitEntity().performCommand(cmd);
                        sender.sendMessage(args[1] + " ran /" + cmd + " -> " + ok);
                    }
                }
                case "chat" -> {
                    Bot bot = bot(sender, args[1]);
                    if (bot != null) bot.getBukkitEntity().chat(String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
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
                        sender.sendMessage(String.format(Locale.ROOT, "%s world=%s pos=%.2f,%.2f,%.2f mode=%s health=%.1f/%.1f food=%d dead=%s flying=%s inv=%s",
                                p.getName(), l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), p.getGameMode(), p.getHealth(),
                                p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(), p.getFoodLevel(), p.isDead(), p.isFlying(),
                                Arrays.stream(p.getInventory().getContents()).filter(Objects::nonNull).map(i -> i.getType() + "x" + i.getAmount()).toList()));
                    }
                }
                default -> { return false; }
            }
        } catch (RuntimeException failure) {
            sender.sendMessage("Error: " + failure);
            getLogger().log(java.util.logging.Level.WARNING, "bots command failed", failure);
        }
        return true;
    }

    private void spawn(CommandSender sender, String name, Location at) {
        if (bots.containsKey(name) || Bukkit.getPlayerExact(name) != null) { sender.sendMessage(name + " is already online"); return; }
        Bot bot = Bot.spawn(this, name, at);
        if (bot != null) { bots.put(name, bot); sender.sendMessage("Spawned " + name); }
        else sender.sendMessage("Could not spawn " + name);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("spawn", "spawnn", "remove", "list", "fight", "cmd", "chat", "tp", "attack", "use", "slot", "look", "walk", "vel", "info");
        return new ArrayList<>(bots.keySet());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Bot bot = bots.get(event.getPlayer().getName());
        if (bot != null && bot.getBukkitEntity() == event.getPlayer()) bots.remove(event.getPlayer().getName());
    }
}
