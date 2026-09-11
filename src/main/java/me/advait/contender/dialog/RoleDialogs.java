package me.advait.contender.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import me.advait.contender.Contender;
import me.advait.contender.role.PlayerRole;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.*;

public final class RoleDialogs {
    private final Contender plugin;
    private final Dialogs dialogs;
    public RoleDialogs(Contender plugin) { this.plugin = plugin; dialogs = new Dialogs(plugin); }
    public void open(Player player) { open(player, 0); }
    private void open(Player player, int page) {
        var players = Bukkit.getOnlinePlayers().stream().sorted(Comparator.comparing(Player::getName)).toList();
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = page * 8; i < Math.min(page * 8 + 8, players.size()); i++) {
            Player target = players.get(i);
            buttons.add(dialogs.button(player, target.getName() + " · " + plugin.getRoleManager().getRole(target.getUniqueId()).label(),
                    (p, view) -> edit(p, target.getUniqueId(), target.getName())));
        }
        if (page > 0) buttons.add(dialogs.button(player, "Previous page", (p, view) -> open(p, page - 1)));
        if ((page + 1) * 8 < players.size()) buttons.add(dialogs.button(player, "Next page", (p, view) -> open(p, page + 1)));
        dialogs.show(player, "Player roles", "Only contestants can enter duels and tournaments.", List.of(), buttons);
    }
    public void edit(Player player, UUID target, String name) {
        PlayerRole current = plugin.getRoleManager().getRole(target);
        dialogs.show(player, "Role for " + name, "Directors and spectators can watch matches. Roles do not grant admin permissions.",
                List.of(DialogInput.singleOption("role", Component.text("Role"), Arrays.stream(PlayerRole.values())
                        .map(role -> Dialogs.option(role.id(), role.label(), role == current)).toList()).build()),
                List.of(dialogs.button(player, "Save", (p, view) -> {
                    PlayerRole role = PlayerRole.parse(Dialogs.text(view, "role"));
                    plugin.getRoleManager().setRole(target, role);
                    Dialogs.tell(p, name + " is now a " + role.id() + ".");
                    open(p);
                }), dialogs.button(player, "Back", (p, view) -> open(p))));
    }
}
