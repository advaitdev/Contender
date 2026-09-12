package me.advait.contender.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.*;
import me.advait.contender.Contender;
import me.advait.contender.combo.*;
import me.advait.contender.player.PlayerIdentityResolver;
import me.advait.contender.tab.TabStyle;
import me.advait.contender.tournament.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;
import static me.advait.contender.dialog.DialogPalette.*;

/** The authoring wizard and live controls share the tournament dialog palette and navigation. */
public final class ComboDialogs {
    private final Contender plugin;
    private final Dialogs dialogs;
    private final Map<UUID, UUID> lookups = new HashMap<>();
    private record Draft(String name, String map, String kit, String players, ComboDifficulty difficulty, int grace) { }
    public ComboDialogs(Contender plugin) { this.plugin = plugin; dialogs = new Dialogs(plugin); }
    private ActionButton button(Player owner, DialogIcon icon, String label, TextColor color, int width, BiConsumer<Player, DialogResponseView> action) {
        return dialogs.button(owner, icon.label(label, color), null, true, width, action);
    }
    private ActionButton action(Player owner, DialogIcon icon, String label, TextColor color, Consumer<Player> action) {
        return button(owner, icon, label, color, 300, (p, v) -> action.accept(p));
    }
    private void menu(Player player, String title, Component body, List<ActionButton> actions, Consumer<Player> back) {
        lookups.remove(player.getUniqueId());
        dialogs.show(player, title, List.of(DialogBody.plainMessage(body, 320)), List.of(), actions, 1, 150,
                back == null ? null : button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> back.accept(p)));
    }
    private void form(Player player, String title, Component body, List<DialogInput> inputs, ActionButton back, ActionButton next) {
        lookups.remove(player.getUniqueId());
        List<ActionButton> actions = new ArrayList<>(); Dialogs.navigationRow(actions, back, next, 150);
        dialogs.show(player, title, List.of(DialogBody.plainMessage(body, 320)), inputs, actions, 2, 150, null);
    }
    public void open(Player player) {
        var manager = plugin.getComboManager(); var run = manager.current();
        if (run == null) { create(player); return; }
        List<ActionButton> actions = new ArrayList<>();
        if (run.terminal()) actions.add(action(player, DialogIcon.TOURNAMENT, "New Tournament", ACCENT, p -> new TournamentDialogs(plugin).formats(p)));
        else if (!manager.active()) actions.add(action(player, DialogIcon.NEXT, "Start Combo", ACCENT, p -> { manager.start(run.id()); open(p); }));
        if (manager.active()) actions.add(action(player, DialogIcon.PREVIEW, "Watch Combo", TEXT, p -> { manager.requireCurrent(run.id()); manager.watch(p); p.closeDialog(); }));
        actions.add(action(player, DialogIcon.BOARD, "Leaderboard", TEXT, p -> standings(p, run.id(), 0)));
        if (!run.terminal()) actions.add(action(player, DialogIcon.PLAYERS, "Manage Players", TEXT, p -> players(p, run.id(), 0)));
        actions.add(action(player, DialogIcon.SETTINGS, "Setup Tools", TEXT, p -> new TournamentDialogs(plugin).tools(p)));
        if (manager.active()) actions.add(action(player, DialogIcon.SAVE, "End Combo", TEXT, p -> end(p, run.id(), false)));
        if (!run.terminal()) actions.add(action(player, DialogIcon.CLOSE, "Cancel Combo", DANGER, p -> end(p, run.id(), true)));
        String name = run.playing() == null ? "Waiting" : run.entry(run.playing()).name();
        menu(player, "Combo", DialogText.paragraphs(TabStyle.read(plugin.getConfig()).header(run.name()), DialogText.lines(
                DialogText.detail("Status", run.statusText()), DialogText.detail("Current Player", name),
                DialogText.detail("Difficulty", run.difficulty().label()), DialogText.detail("Retry Grace", run.graceHits() + " hits")),
                DialogText.muted("Players take one turn each.\nThe bot waits for the first sword hit.")), actions, null);
    }
    private void end(Player player, UUID event, boolean cancel) {
        plugin.getComboManager().requireCurrent(event);
        menu(player, cancel ? "Cancel Combo" : "End Combo", DialogText.muted(cancel
                        ? "Return everyone to the lobby and clear Combo from tab."
                        : "Keep the saved scores. Players who have not finished will be marked Did Not Finish."),
                List.of(action(player, cancel ? DialogIcon.CLOSE : DialogIcon.SAVE, cancel ? "Cancel Combo" : "End Combo", cancel ? DANGER : ACCENT, p -> {
                    plugin.getComboManager().requireCurrent(event); plugin.getComboManager().end(cancel ? ComboRun.State.CANCELLED : ComboRun.State.FINISHED); open(p);
                })), this::open);
    }
    public void create(Player player) {
        var maps = plugin.getMapManager().getMaps().stream().filter(m -> m.isComplete()).toList();
        var kits = new ArrayList<>(plugin.getKitManager().getKits());
        if (maps.isEmpty() || kits.isEmpty()) {
            menu(player, "Set Up Combo", DialogText.muted("Save a map with player and bot spawns, then save a sword kit.\nUse Team 1 for the player and Team 2 for the bot."), List.of(
                    action(player, DialogIcon.MAP, "Combo Arenas", ACCENT, p -> maps(p, 0)),
                    action(player, DialogIcon.DUEL, "Kits", TEXT, p -> new KitDialogs(plugin).open(p, this::create))), p -> new TournamentDialogs(plugin).formats(p)); return;
        }
        details(player, new Draft("Combo", maps.getFirst().getId(), kits.getFirst().getId(),
                String.join(", ", Bukkit.getOnlinePlayers().stream().filter(p -> plugin.getRoleManager().isContestant(p.getUniqueId())).map(Player::getName).toList()), ComboDifficulty.NORMAL, 5));
    }
    private void details(Player player, Draft draft) {
        form(player, "Combo · Details", DialogText.muted("One player faces the mannequin at a time.\nThe longest combo wins."), List.of(
                DialogInput.text("name", DialogIcon.NAME.label("Tournament Name")).initial(draft.name()).width(300).maxLength(64).build(),
                DialogInput.singleOption("map", DialogIcon.MAP.label("Arena"), plugin.getMapManager().getMaps().stream().filter(m -> m.isComplete())
                        .map(m -> Dialogs.option(m.getId(), m.getDisplayName(), m.getId().equals(draft.map()))).toList()).width(300).build(),
                DialogInput.singleOption("kit", DialogIcon.DUEL.label("Player Kit"), plugin.getKitManager().getKits().stream().map(k ->
                        SingleOptionDialogInput.OptionEntry.create(k.getId(), KitIcons.label(k), k.getId().equals(draft.kit()))).toList()).width(300).build()),
                button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> new TournamentDialogs(plugin).formats(p)),
                button(player, DialogIcon.NEXT, "Next: Players", ACCENT, 150, (p, v) -> {
                    String name = Dialogs.text(v, "name"); if (name.isBlank()) throw new IllegalArgumentException("Give the event a name.");
                    roster(p, new Draft(name, Dialogs.text(v, "map"), Dialogs.text(v, "kit"), draft.players(), draft.difficulty(), draft.grace()));
                }));
    }
    private void roster(Player player, Draft draft) {
        form(player, "Combo · Players", DialogText.muted("Enter names separated by commas or spaces.\nOffline players can join the queue in advance.\nOnline players take their turns in this order."), List.of(
                DialogInput.text("players", DialogIcon.PLAYERS.label("Players")).initial(draft.players()).width(300).maxLength(4096)
                        .multiline(TextDialogInput.MultilineOptions.create(8, 150)).build()),
                button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> details(p, withPlayers(draft, Dialogs.text(v, "players")))),
                button(player, DialogIcon.NEXT, "Next: Rules", ACCENT, 150, (p, v) -> rules(p, withPlayers(draft, Dialogs.text(v, "players")))));
    }
    private static Draft withPlayers(Draft d, String players) { return new Draft(d.name(), d.map(), d.kit(), players, d.difficulty(), d.grace()); }
    private void rules(Player player, Draft draft) {
        form(player, "Combo · Rules", DialogText.muted("Difficulty changes the bot's reach, movement, reaction time, and sword swing interval.\n\nA returned hit within the retry grace starts a fresh attempt.\nWith 5, you can retry while your combo is 1–5.\nAfter that, a returned hit ends your turn.\n\nKnock the bot into the void to score ∞."), List.of(
                DialogInput.singleOption("difficulty", DialogIcon.DUEL.label("Bot Difficulty"), Arrays.stream(ComboDifficulty.values()).map(d -> Dialogs.option(d.name(), d.label(), d == draft.difficulty())).toList()).width(300).build(),
                DialogInput.text("grace", DialogIcon.REFRESH.label("Retry Grace (hits)")).initial(String.valueOf(draft.grace())).maxLength(3).width(300).build()),
                button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> roster(p, readRules(v, draft))),
                button(player, DialogIcon.NEXT, "Next: Review", ACCENT, 150, (p, v) -> resolve(p, readRules(v, draft))));
    }
    private Draft readRules(DialogResponseView view, Draft draft) {
        int grace;
        try { grace = Integer.parseInt(Dialogs.text(view, "grace")); } catch (NumberFormatException error) { throw new IllegalArgumentException("Enter a whole number for retry grace."); }
        if (grace < 0 || grace > 100) throw new IllegalArgumentException("Choose between 0 and 100 retry hits.");
        return new Draft(draft.name(), draft.map(), draft.kit(), draft.players(), ComboDifficulty.valueOf(Dialogs.text(view, "difficulty")), grace);
    }
    private void resolve(Player player, Draft draft) {
        String[] names = draft.players().strip().split("[,\\s]+");
        CompletableFuture<List<TournamentEntry>> future;
        if (names.length == 1 && !names[0].isBlank()) future = PlayerIdentityResolver.resolve(names[0]).thenApply(i -> List.of(new TournamentEntry(i.name(), List.of(i.id()), Map.of(i.id(), i.name()))));
        else future = RosterParser.parseAsync(draft.players(), false, PlayerIdentityResolver::resolve);
        menu(player, "Finding Players", DialogText.muted("Checking player names…"), List.of(), p -> roster(p, draft));
        UUID token = UUID.randomUUID(); lookups.put(player.getUniqueId(), token);
        future.whenComplete((entries, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || !player.hasPermission("contender.master") || !token.equals(lookups.get(player.getUniqueId()))) return;
                lookups.remove(player.getUniqueId(), token);
                if (error != null) { Dialogs.tell(player, ComboManager.message(error)); roster(player, draft); return; }
                review(player, draft, entries);
            });
        });
    }
    private void review(Player player, Draft draft, List<TournamentEntry> entries) {
        var map = plugin.getMapManager().getMap(draft.map()); var kit = plugin.getKitManager().getKit(draft.kit());
        if (map == null || kit == null) throw new IllegalArgumentException("The selected map or kit was removed. Choose them again.");
        form(player, "Review Combo", DialogText.paragraphs(TabStyle.read(plugin.getConfig()).header(draft.name()), DialogText.lines(
                        DialogText.detail("Arena", map.getDisplayName()), KitIcons.label(kit), DialogText.detail("Players", String.valueOf(entries.size())),
                        DialogText.detail("Difficulty", draft.difficulty().label()), DialogText.detail("Retry Grace", draft.grace() + " hits")),
                DialogText.muted("Players keep full hunger and take no health damage.\nThe bot starts on the first sword hit.")), List.of(),
                button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> rules(p, draft)),
                button(player, DialogIcon.SAVE, "Create Combo", ACCENT, 150, (p, v) -> {
                    plugin.getComboManager().create(draft.name(), draft.map(), draft.kit(), draft.difficulty(), draft.grace(), entries); open(p);
                }));
    }
    public void maps(Player player, int requested) {
        var maps = new ArrayList<>(plugin.getMapManager().getMaps());
        int last = Math.max(0, (maps.size() - 1) / 8), page = Math.clamp(requested, 0, last);
        List<ActionButton> actions = new ArrayList<>();
        for (var map : maps.subList(Math.min(page * 8, maps.size()), Math.min(maps.size(), page * 8 + 8))) actions.add(button(player, DialogIcon.MAP, map.getDisplayName(), TEXT, 150, (p, v) -> map(p, map.getId())));
        actions.add(button(player, DialogIcon.MAP, "Manage Maps", TEXT, 150, (p, v) -> new ArenaDialogs(plugin).open(p)));
        Dialogs.navigationRow(actions, page > 0 ? button(player, DialogIcon.BACK, "Previous", MUTED, 150, (p, v) -> maps(p, page - 1)) : null,
                page < last ? button(player, DialogIcon.NEXT, "Next", ACCENT, 150, (p, v) -> maps(p, page + 1)) : null, 150);
        dialogs.show(player, "Combo Arenas", List.of(DialogBody.plainMessage(DialogText.muted("Choose a saved map to set player and bot spawns.\nUse a platform surrounded by void for ∞ clears."), 320)), List.of(), actions, 2, 150,
                button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> new TournamentDialogs(plugin).tools(p)));
    }
    private void map(Player player, String id) {
        var map = plugin.getMapManager().getMap(id); if (map == null) throw new IllegalArgumentException("This map was removed.");
        menu(player, map.getDisplayName(), DialogText.paragraphs(DialogText.lines(DialogText.detail("Map ID", id),
                        DialogText.detail("Player Spawn", map.getTeam1Point() == null ? "Not Set" : "Saved"),
                        DialogText.detail("Bot Spawn", map.getTeam2Point() == null ? "Not Set" : "Saved"),
                        DialogText.detail("Ready Copies", String.valueOf(plugin.getArenaManager().available(id)))),
                DialogText.muted("Stand inside the original map selection to set each spawn.\nThese use the map's Team 1 and Team 2 spawns.")), List.of(
                action(player, DialogIcon.SAVE, "Save & Rebuild Copies", ACCENT, p -> {
                    editable(id); p.closeDialog(); Dialogs.tell(p, "Rebuilding the Combo arena copies…");
                    plugin.getArenaManager().saveBlocks(map).thenCompose(ignored -> plugin.getArenaManager().prepare(map)).whenComplete((ignored, error) -> {
                        if (!plugin.isEnabled()) return;
                        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) Dialogs.tell(p, error == null ? "Arena copies are ready." : ComboManager.message(error)); });
                    });
                }),
                action(player, DialogIcon.PLAYERS, "Set Player Spawn Here", TEXT, p -> { editable(id); plugin.getArenaManager().setSpawn(map, "1", p.getLocation()); map(p, id); }),
                action(player, DialogIcon.DUEL, "Set Bot Spawn Here", TEXT, p -> { editable(id); plugin.getArenaManager().setSpawn(map, "2", p.getLocation()); map(p, id); }),
                action(player, DialogIcon.PREVIEW, "Set Spectator Spawn Here", TEXT, p -> { editable(id); plugin.getArenaManager().setSpawn(map, "spectator", p.getLocation()); map(p, id); })), p -> maps(p, 0));
    }
    private void editable(String id) {
        var manager = plugin.getComboManager(); var run = manager.current();
        if (run != null && run.mapId().equals(id) && (!run.terminal() || manager.busy())) throw new IllegalStateException("Finish or cancel Combo before changing this map.");
    }
    private void standings(Player player, UUID event, int requested) {
        plugin.getComboManager().requireCurrent(event); var run = plugin.getComboManager().current(); var entries = run.standings();
        int last = (entries.size() - 1) / 12, page = Math.clamp(requested, 0, last); Component text = Component.empty();
        for (int i = page * 12; i < Math.min(entries.size(), page * 12 + 12); i++) {
            var entry = entries.get(i); text = text.append(Component.text((i + 1) + ". ", ACCENT))
                    .append(plugin.getNameTagManager().displayName(entry.id(), entry.name())).append(Component.text("  " + entry.value(), TEXT)).appendNewline();
        }
        List<ActionButton> buttons = new ArrayList<>();
        Dialogs.navigationRow(buttons, page > 0 ? button(player, DialogIcon.BACK, "Previous", MUTED, 150, (p, v) -> standings(p, event, page - 1)) : null,
                page < last ? button(player, DialogIcon.NEXT, "Next", ACCENT, 150, (p, v) -> standings(p, event, page + 1)) : null, 150);
        dialogs.show(player, "Combo Leaderboard", List.of(DialogBody.plainMessage(text, 320)), List.of(), buttons, 2, 150, button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> open(p)));
    }
    private void players(Player player, UUID event, int requested) {
        plugin.getComboManager().requireCurrent(event); var entries = plugin.getComboManager().current().entries();
        int last = (entries.size() - 1) / 10, page = Math.clamp(requested, 0, last); List<ActionButton> buttons = new ArrayList<>();
        for (var entry : entries.subList(page * 10, Math.min(entries.size(), page * 10 + 10))) if (!entry.done()) buttons.add(button(player, DialogIcon.PLAYERS, entry.name(), TEXT, 150, (p, v) ->
                menu(p, "Skip Player", DialogText.muted("End " + entry.name() + "'s turn?\nTheir current combo is saved if it passed the retry grace.\nOtherwise they are marked Did Not Finish."),
                        List.of(action(p, DialogIcon.CLOSE, "Skip " + entry.name(), DANGER, who -> { plugin.getComboManager().requireCurrent(event); plugin.getComboManager().withdraw(entry.id()); open(who); })), this::open)));
        Dialogs.navigationRow(buttons, page > 0 ? button(player, DialogIcon.BACK, "Previous", MUTED, 150, (p, v) -> players(p, event, page - 1)) : null,
                page < last ? button(player, DialogIcon.NEXT, "Next", ACCENT, 150, (p, v) -> players(p, event, page + 1)) : null, 150);
        dialogs.show(player, "Manage Combo Players", List.of(DialogBody.plainMessage(DialogText.muted("Choose a player to skip their turn."), 320)), List.of(), buttons, 2, 150, button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> open(p)));
    }
}
