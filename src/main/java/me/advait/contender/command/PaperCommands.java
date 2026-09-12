package me.advait.contender.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import me.advait.contender.Contender;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Keeps existing executors and command metadata while registering through Paper's lifecycle. */
public final class PaperCommands {
    private final Contender plugin;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    public PaperCommands(Contender plugin) {
        this.plugin = plugin;
        try (var reader = new InputStreamReader(Objects.requireNonNull(plugin.getResource("plugin.yml")), StandardCharsets.UTF_8)) {
            var commands = Objects.requireNonNull(YamlConfiguration.loadConfiguration(reader).getConfigurationSection("commands"));
            for (String name : commands.getKeys(false)) {
                var entry = new Entry(name);
                entry.setDescription(commands.getString(name + ".description", ""));
                entry.setUsage(commands.getString(name + ".usage", "/" + name));
                entry.setPermission(commands.getString(name + ".permission"));
                entries.put(name, entry);
            }
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    public Entry command(String name) { return Objects.requireNonNull(entries.get(name), name); }
    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            for (var entry : entries.values()) {
                if (entry.executor == null) throw new IllegalStateException("No executor for /" + entry.getName());
                event.registrar().register(entry.getName(), entry.getDescription(), entry.getAliases(), new BasicCommand() {
                    @Override public boolean canUse(CommandSender sender) { return entry.allowed(sender); }
                    @Override public void execute(CommandSourceStack source, String[] args) { entry.execute(source.getSender(), entry.getName(), args); }
                    @Override public Collection<String> suggest(CommandSourceStack source, String[] args) { return entry.tabComplete(source.getSender(), entry.getName(), args); }
                });
            }
        });
    }
    public final class Entry extends Command {
        private CommandExecutor executor;
        private TabCompleter completer;
        private Entry(String name) { super(name); }
        public void setExecutor(CommandExecutor executor) { this.executor = executor; }
        public void setTabCompleter(TabCompleter completer) { this.completer = completer; }
        private boolean allowed(CommandSender sender) {
            if (getName().equals("hacks")) return sender instanceof Player player && plugin.getHackerManager().isHacker(player.getUniqueId());
            return getPermission() == null || sender.hasPermission(getPermission());
        }
        @Override public boolean execute(CommandSender sender, String label, String[] args) {
            if (!allowed(sender)) return true;
            return executor.onCommand(sender, this, label, args);
        }
        @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (!allowed(sender)) return List.of();
            TabCompleter selected = completer != null ? completer : executor instanceof TabCompleter tab ? tab : null;
            var result = selected == null ? null : selected.onTabComplete(sender, this, alias, args);
            return result == null ? List.of() : result;
        }
    }
}
