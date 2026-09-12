package me.advait.contender.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import me.advait.contender.Contender;
import me.advait.contender.role.PlayerRole;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
            buttons.add(dialogs.button(player, Component.textOfChildren(me.advait.contender.util.StringUtil.getPlayerHead(target),
                    Component.text(" " + target.getName(), NamedTextColor.WHITE),
                    DialogText.muted(" · " + plugin.getRoleManager().getRole(target.getUniqueId()).label())), true,
                    (p, view) -> edit(p, target.getUniqueId(), target.getName())));
        }
        Dialogs.navigationRow(buttons,
                page > 0 ? dialogs.button(player, DialogIcon.BACK, "Previous Page", (p, view) -> open(p, page - 1)) : null,
                (page + 1) * 8 < players.size() ? dialogs.button(player, DialogIcon.NEXT, "Next Page", (p, view) -> open(p, page + 1)) : null);
        dialogs.show(player, "Player Roles", DialogText.paragraphs(
                DialogText.muted("Only contestants can enter duels and tournaments."),
                DialogText.page(page + 1, Math.max(1, (players.size() + 7) / 8))), List.of(), buttons);
    }
    public void edit(Player player, UUID target, String name) {
        PlayerRole current = plugin.getRoleManager().getRole(target);
        dialogs.show(player, "Role for " + name, List.of(io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(
                DialogText.paragraphs(DialogText.detail("Current role", current.label()),
                        DialogText.muted("Spectators enter Spectator mode. Contestants can play.\nDirectors and spectators can watch matches. Roles do not grant admin permissions."),
                        DialogText.muted("Making a contestant a spectator or director pauses their stage and cancels their active match. Completed results are kept.")), 320)),
                List.of(DialogInput.singleOption("role", DialogIcon.PLAYERS.label("Role"), Arrays.stream(PlayerRole.values())
                        .map(role -> Dialogs.option(role.id(), role.label(), role == current)).toList()).width(300).build()),
                List.of(dialogs.button(player, DialogIcon.BACK.label("Back", DialogPalette.MUTED), null, true, 150, (p, view) -> open(p)),
                        dialogs.button(player, DialogIcon.SAVE.label("Save", DialogPalette.ACCENT), null, true, 150, (p, view) -> {
                            PlayerRole role = PlayerRole.parse(Dialogs.text(view, "role"));
                            plugin.getRoleManager().setRole(target, role);
                            Dialogs.tell(p, name + " is now a " + role.id() + ".");
                            open(p);
                        })), 2, 150, null);
    }
}
