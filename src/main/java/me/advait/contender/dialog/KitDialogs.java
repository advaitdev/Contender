package me.advait.contender.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import me.advait.contender.Contender;
import me.advait.contender.gui.duel.KitEditorGUI;
import me.advait.contender.kit.Kit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static me.advait.contender.dialog.DialogPalette.*;

public final class KitDialogs {
    private final Contender plugin;
    private final Dialogs dialogs;
    public KitDialogs(Contender plugin) { this.plugin = plugin; dialogs = new Dialogs(plugin); }
    public void open(Player player, Consumer<Player> back) { open(player, 0, back); }
    private void open(Player player, int requestedPage, Consumer<Player> back) {
        var kits = plugin.getKitManager().getKits().stream().sorted(Comparator.comparing(Kit::getDisplayName)).toList();
        int pages = Math.max(1, (kits.size() + 7) / 8), page = Math.clamp(requestedPage, 0, pages - 1);
        List<ActionButton> buttons = new ArrayList<>();
        for (Kit kit : kits.subList(page * 8, Math.min(kits.size(), page * 8 + 8))) {
            buttons.add(dialogs.button(player, KitIcons.label(kit), DialogText.muted("Edit this kit's items and rules."), true, 220, (p, view) -> {
                if (plugin.getKitManager().getKit(kit.getId()) != kit) throw new IllegalStateException("This kit changed. Open the kit list again.");
                p.closeDialog(); KitEditorGUI.open(p, kit, false);
            }));
        }
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.SAVE.label("Create New Kit", ACCENT), null, true, 220, (p, view) -> create(p, back)),
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, 220, (p, view) -> back.accept(p)));
        Dialogs.navigationRow(buttons,
                page > 0 ? dialogs.button(player, DialogIcon.BACK, "Previous Page", (p, view) -> open(p, page - 1, back)) : null,
                page + 1 < pages ? dialogs.button(player, DialogIcon.NEXT, "Next Page", (p, view) -> open(p, page + 1, back)) : null);
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(DialogText.muted(kits.isEmpty() ? "Create a kit to set up its items and rules." : "Choose a kit to edit its items and rules."), 320));
        if (pages > 1) body.add(DialogBody.plainMessage(DialogText.page(page + 1, pages), 320));
        dialogs.show(player, "Kits", body, List.of(), buttons, 2, 150, null);
    }
    private void create(Player player, Consumer<Player> back) {
        dialogs.show(player, "New Kit", List.of(DialogBody.plainMessage(DialogText.muted("Name the kit, then fill its inventory and save it."), 320)),
                List.of(DialogInput.text("kit_name", DialogIcon.NAME.label("Kit Name")).initial("").maxLength(48).width(300).build()),
                List.of(dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, 150, (p, view) -> open(p, back)),
                        dialogs.button(player, DialogIcon.NEXT.label("Next: Items", ACCENT), null, true, 150, (p, view) -> {
                            String name = Dialogs.text(view, "kit_name");
                            if (name.isBlank()) throw new IllegalArgumentException("Give the kit a name.");
                            String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
                            if (id.chars().noneMatch(Character::isLetterOrDigit)) throw new IllegalArgumentException("Include a letter or number in the kit name.");
                            if (plugin.getKitManager().kitExists(id)) throw new IllegalArgumentException("A kit with that name already exists.");
                            Kit kit = new Kit(id); kit.setDisplayName(name);
                            p.closeDialog(); KitEditorGUI.open(p, kit, true);
                        })), 2, 150, null);
    }
}
