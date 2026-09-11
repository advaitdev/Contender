package me.advait.contender.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import me.advait.contender.Contender;
import me.advait.contender.gui.settings.SettingsGUI;
import me.advait.contender.role.PlayerRole;
import me.advait.contender.role.RoleStyle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import java.util.*;

public final class SettingsDialogs {
    private final Contender plugin;
    private final Dialogs dialogs;
    public SettingsDialogs(Contender plugin) { this.plugin = plugin; dialogs = new Dialogs(plugin); }
    public void open(Player player) {
        List<ActionButton> buttons = new ArrayList<>();
        if (player.hasPermission("contender.master")) {
            for (PlayerRole role : List.of(PlayerRole.DIRECTOR, PlayerRole.SPECTATOR)) {
                buttons.add(dialogs.button(player, role.label() + " nametags", (p, view) -> style(p, role)));
            }
            buttons.add(dialogs.button(player, "Player roles", (p, view) -> new RoleDialogs(plugin).open(p)));
        }
        buttons.add(dialogs.button(player, "Disguise, chat and PvP", false, (p, view) -> { p.closeDialog(); SettingsGUI.open(p); }));
        dialogs.show(player, "Settings", "Your role: " + plugin.getRoleManager().getRole(player.getUniqueId()).label(), List.of(), buttons);
    }
    private void style(Player player, PlayerRole role) {
        RoleStyle style = RoleStyle.read(plugin.getConfig(), role);
        List<DialogInput> inputs = List.of(Dialogs.text("prefix", "Prefix", style.prefix(), 32),
                DialogInput.singleOption("color", Component.text("Prefix and name color"), NamedTextColor.NAMES.keys().stream()
                        .map(name -> Dialogs.option(name, name.replace('_', ' '), NamedTextColor.NAMES.value(name).equals(style.color()))).toList()).build());
        dialogs.show(player, role.label() + " nametags", "Applies to everyone with this role. Leave the prefix empty to show only their name.", inputs,
                List.of(dialogs.button(player, "Save", (p, view) -> {
                    RoleStyle changed = new RoleStyle(Dialogs.text(view, "prefix"), RoleStyle.parseColor(Dialogs.text(view, "color")));
                    changed.write(plugin.getConfig(), role);
                    plugin.saveConfig();
                    plugin.getNameTagManager().refresh();
                    Dialogs.tell(p, role.label() + " nametags updated.");
                    open(p);
                }), dialogs.button(player, "Back", (p, view) -> open(p))));
    }
}
