package me.advait.contender.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import me.advait.contender.Contender;
import me.advait.contender.manhunt.*;
import me.advait.contender.player.PlayerIdentityResolver;
import me.advait.contender.tab.TabStyle;
import me.advait.contender.tournament.RosterParser;
import me.advait.contender.tournament.TournamentEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.function.*;
import static me.advait.contender.dialog.DialogPalette.*;

public final class ManhuntDialogs {
    private record Draft(UUID previous, String name, String kit, boolean random, String roster, String runners, String hunters, int seconds) { }
    private final Contender plugin;
    private final Dialogs dialogs;
    private final Map<UUID, UUID> lookups = new HashMap<>();
    public ManhuntDialogs(Contender plugin) { this.plugin = plugin; dialogs = new Dialogs(plugin); }
    private ManhuntManager manager() { return plugin.getManhuntManager(); }
    private ActionButton button(Player owner, DialogIcon icon, String text, TextColor color, int width, BiConsumer<Player, DialogResponseView> callback) {
        return dialogs.button(owner, icon.label(text, color), null, true, width, callback);
    }
    private ActionButton action(Player owner, DialogIcon icon, String text, TextColor color, Consumer<Player> callback) {
        return button(owner, icon, text, color, 300, (p, v) -> callback.accept(p));
    }
    private void menu(Player player, String title, Component body, List<ActionButton> actions, Consumer<Player> back) {
        lookups.remove(player.getUniqueId());
        dialogs.show(player, title, List.of(DialogBody.plainMessage(body, 320)), List.of(), actions, 1, 150,
                back == null ? null : button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> back.accept(p)));
    }
    private void form(Player player, String title, Component body, List<DialogInput> inputs, ActionButton back, ActionButton next) {
        lookups.remove(player.getUniqueId()); List<ActionButton> actions = new ArrayList<>(); Dialogs.navigationRow(actions, back, next, 150);
        dialogs.show(player, title, List.of(DialogBody.plainMessage(body, 320)), inputs, actions, 2, 150, null);
    }
    public void open(Player player) {
        var game = manager().current();
        if (game == null) { create(player); return; }
        List<ActionButton> actions = new ArrayList<>();
        if (game.terminal()) actions.add(action(player, DialogIcon.TOURNAMENT, "New Tournament", ACCENT, p -> new TournamentDialogs(plugin).formats(p)));
        else if (!manager().active()) actions.add(action(player, DialogIcon.NEXT, "Start Manhunt", ACCENT, p -> { manager().start(game.id()); open(p); }));
        actions.add(action(player, DialogIcon.PLAYERS, "Teams", TEXT, p -> teams(p, game.id(), 0)));
        actions.add(action(player, DialogIcon.SETTINGS, "Setup Tools", TEXT, p -> new TournamentDialogs(plugin).tools(p)));
        if (!game.terminal()) actions.add(action(player, DialogIcon.CLOSE, "Cancel Manhunt", DANGER, p -> cancel(p, game.id())));
        menu(player, "Manhunt", DialogText.paragraphs(TabStyle.read(plugin.getConfig()).header(game.name()), DialogText.lines(
                DialogText.detail("Status", game.statusText()), DialogText.detail("Runners", game.alive(ManhuntRun.Team.RUNNER) + " Remaining"),
                DialogText.detail("Hunters", game.alive(ManhuntRun.Team.HUNTER) + " Remaining"), DialogText.detail("Kit", kitName(game.kitId())))), actions, null);
    }
    private void cancel(Player player, UUID id) {
        manager().requireCurrent(id);
        menu(player, "Cancel Manhunt", DialogText.muted("Return everyone to the lobby and clear this game from tab."), List.of(
                action(player, DialogIcon.CLOSE, "Cancel Manhunt", DANGER, p -> { manager().cancel(id); open(p); })), this::open);
    }
    public void setup(Player player) {
        List<ActionButton> actions = new ArrayList<>();
        if (!manager().busy() && manager().terminal()) actions.add(action(player, DialogIcon.CRYSTAL, manager().worldReady() ? "Prepare a Fresh End" : "Prepare End World", ACCENT, this::prepare));
        actions.add(action(player, DialogIcon.DUEL, "Kits", TEXT, p -> new KitDialogs(plugin).open(p, this::setup)));
        menu(player, "Manhunt Setup", DialogText.paragraphs(DialogText.detail("End World", manager().worldStatus()),
                DialogText.muted("Prepare the End before creating a game.\nEveryone starts on an obsidian pillar.\n\nEach game uses a fresh world. Old worlds stay on disk and can be removed while the server is stopped.")), actions, p -> new TournamentDialogs(plugin).tools(p));
    }
    private void prepare(Player player) {
        menu(player, "Prepare End World", DialogText.muted("Generate a fresh End and load the island before the game.\nThis can take a moment."), List.of(
                action(player, DialogIcon.CRYSTAL, "Prepare End World", ACCENT, p -> {
                    var future = manager().prepareWorld(); setup(p);
                    future.whenComplete((ignored, error) -> {
                        if (!plugin.isEnabled()) return;
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!p.isOnline()) return;
                            Dialogs.tell(p, error == null ? "The End is ready. You can create Manhunt now." : "The End could not be prepared: " + rootMessage(error));
                        });
                    });
                })), this::setup);
    }
    public void create(Player player) {
        if (!manager().worldReady() || plugin.getKitManager().getKits().isEmpty()) {
            menu(player, "Set Up Manhunt", DialogText.muted("Prepare an End world and save a kit first."), List.of(
                    action(player, DialogIcon.CRYSTAL, "Manhunt Setup", ACCENT, this::setup),
                    action(player, DialogIcon.DUEL, "Kits", TEXT, p -> new KitDialogs(plugin).open(p, this::setup))), p -> new TournamentDialogs(plugin).formats(p));
            return;
        }
        String roster = String.join(", ", Bukkit.getOnlinePlayers().stream().filter(p -> plugin.getRoleManager().isContestant(p.getUniqueId())).map(Player::getName).toList());
        details(player, new Draft(manager().eventId(), "Manhunt", plugin.getKitManager().getKits().iterator().next().getId(), true, roster, "", "", 10));
    }
    private void current(Draft draft) {
        if (!Objects.equals(draft.previous(), manager().eventId())) throw new IllegalStateException("The selected Manhunt changed. Open its setup again.");
    }
    private void details(Player player, Draft draft) {
        form(player, "Manhunt · Details", DialogText.muted("Runners win when the dragon dies.\nHunters win when every runner is out.\nBoth teams have one life."), List.of(
                DialogInput.text("name", DialogIcon.NAME.label("Tournament Name")).initial(draft.name()).maxLength(64).width(300).build(),
                DialogInput.singleOption("kit", DialogIcon.DUEL.label("Kit"), plugin.getKitManager().getKits().stream().map(k -> io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.OptionEntry.create(k.getId(), KitIcons.label(k), draft.kit().equals(k.getId()))).toList()).width(300).build(),
                DialogInput.singleOption("teams", DialogIcon.PLAYERS.label("Teams"), List.of(Dialogs.option("random", "Random Teams", draft.random()), Dialogs.option("manual", "Choose Teams", !draft.random()))).width(300).build()),
                button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> new TournamentDialogs(plugin).formats(p)),
                button(player, DialogIcon.NEXT, "Next: Players", ACCENT, 150, (p, v) -> {
                    current(draft); String name = Dialogs.text(v, "name"); if (name.isBlank()) throw new IllegalArgumentException("Give the tournament a name.");
                    roster(p, new Draft(draft.previous(), name, Dialogs.text(v, "kit"), "random".equals(Dialogs.text(v, "teams")), draft.roster(), draft.runners(), draft.hunters(), draft.seconds()));
                }));
    }
    private DialogInput names(String key, String label, String initial) {
        return DialogInput.text(key, DialogIcon.PLAYERS.label(label)).initial(initial).maxLength(2048).width(300).multiline(TextDialogInput.MultilineOptions.create(64, 100)).build();
    }
    private void roster(Player player, Draft draft) {
        List<DialogInput> inputs = draft.random() ? List.of(names("players", "Contestants", draft.roster())) : List.of(names("runners", "Runners", draft.runners()), names("hunters", "Hunters", draft.hunters()));
        form(player, "Manhunt · Players", DialogText.muted(draft.random()
                        ? "Enter names, separated by commas.\nOffline players can be entered now.\nThe teams will be split evenly and shown for review."
                        : "Enter names, separated by commas.\nEach team needs at least one player.\nA player can only be on one team."), inputs,
                button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> details(p, readRoster(v, draft))),
                button(player, DialogIcon.NEXT, "Next: Rules", ACCENT, 150, (p, v) -> rules(p, readRoster(v, draft))));
    }
    private Draft readRoster(DialogResponseView view, Draft draft) {
        return new Draft(draft.previous(), draft.name(), draft.kit(), draft.random(), draft.random() ? Dialogs.text(view, "players") : draft.roster(), draft.random() ? draft.runners() : Dialogs.text(view, "runners"), draft.random() ? draft.hunters() : Dialogs.text(view, "hunters"), draft.seconds());
    }
    private void rules(Player player, Draft draft) {
        form(player, "Manhunt · Rules", DialogText.muted("Players, mobs, and the world stay still during the countdown.\n\nDeaths and disconnects count as being out.\nPlayers who are out can watch until the game ends.\nBlock and damage rules come from the selected kit."), List.of(
                DialogInput.text("seconds", DialogIcon.REFRESH.label("Countdown (seconds)")).initial(String.valueOf(draft.seconds())).maxLength(3).width(300).build()),
                button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> roster(p, readRules(v, draft))),
                button(player, DialogIcon.NEXT, "Next: Review", ACCENT, 150, (p, v) -> resolve(p, readRules(v, draft))));
    }
    private Draft readRules(DialogResponseView view, Draft draft) {
        int seconds;
        try { seconds = Integer.parseInt(Dialogs.text(view, "seconds")); } catch (NumberFormatException error) { throw new IllegalArgumentException("Enter a countdown between 3 and 120 seconds."); }
        if (seconds < 3 || seconds > 120) throw new IllegalArgumentException("Enter a countdown between 3 and 120 seconds.");
        return new Draft(draft.previous(), draft.name(), draft.kit(), draft.random(), draft.roster(), draft.runners(), draft.hunters(), seconds);
    }
    private void resolve(Player player, Draft draft) {
        current(draft);
        String input = draft.random() ? draft.roster() : "Runners: " + draft.runners().replace('\n', ',') + "\nHunters: " + draft.hunters().replace('\n', ',');
        var future = RosterParser.parseAsync(input, !draft.random(), PlayerIdentityResolver::resolve);
        menu(player, "Finding Players", DialogText.muted("Checking player names…"), List.of(), p -> roster(p, draft));
        UUID token = UUID.randomUUID(); lookups.put(player.getUniqueId(), token);
        future.whenComplete((entries, failure) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || !player.hasPermission("contender.master") || !token.equals(lookups.get(player.getUniqueId()))) return;
                lookups.remove(player.getUniqueId(), token);
                try {
                    current(draft); if (failure != null) throw new IllegalArgumentException(rootMessage(failure));
                    List<ManhuntRun.Entry> roster = new ArrayList<>();
                    for (int i = 0; i < entries.size(); i++) {
                        TournamentEntry entry = entries.get(i); ManhuntRun.Team team = i == 0 ? ManhuntRun.Team.RUNNER : ManhuntRun.Team.HUNTER;
                        for (UUID id : entry.players()) { plugin.getDuelManager().requireEligible(id); roster.add(new ManhuntRun.Entry(id, entry.playerNames().getOrDefault(id, entry.name()), team)); }
                    }
                    if (draft.random()) roster = ManhuntRun.randomTeams(roster, new Random());
                    // Validate duplicates and team sizes before showing the final review.
                    new ManhuntRun(UUID.randomUUID(), draft.name(), draft.kit(), manager().worldName(), roster, draft.seconds());
                    review(player, draft, roster);
                } catch (IllegalArgumentException | IllegalStateException error) { Dialogs.tell(player, error.getMessage()); roster(player, draft); }
            });
        });
    }
    private void review(Player player, Draft draft, List<ManhuntRun.Entry> entries) {
        form(player, "Review Manhunt", DialogText.paragraphs(TabStyle.read(plugin.getConfig()).header(draft.name()), DialogText.lines(
                DialogText.detail("Kit", kitName(draft.kit())), DialogText.detail("Countdown", draft.seconds() + " Seconds")),
                teamNames(entries, ManhuntRun.Team.RUNNER), teamNames(entries, ManhuntRun.Team.HUNTER)), List.of(),
                button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> rules(p, draft)),
                button(player, DialogIcon.SAVE, "Create Manhunt", ACCENT, 150, (p, v) -> { current(draft); manager().create(draft.name(), draft.kit(), entries, draft.seconds()); open(p); }));
    }
    private Component teamNames(List<ManhuntRun.Entry> entries, ManhuntRun.Team team) {
        return DialogText.detail(team == ManhuntRun.Team.RUNNER ? "Runners" : "Hunters", String.join(", ", entries.stream().filter(e -> e.team() == team).map(ManhuntRun.Entry::name).toList()));
    }
    private void teams(Player player, UUID id, int page) {
        manager().requireCurrent(id); var game = manager().current(); int pages = Math.max(1, (game.entries().size() + 9) / 10); int selected = Math.max(0, Math.min(page, pages - 1));
        List<ActionButton> actions = new ArrayList<>();
        for (var entry : game.entries().subList(selected * 10, Math.min(selected * 10 + 10, game.entries().size()))) {
            Component label = me.advait.contender.util.StringUtil.getPlayerHead(entry.id()).append(DialogPalette.text(" " + entry.name() + " · " + (entry.team() == ManhuntRun.Team.RUNNER ? "Runner" : "Hunter") + (game.alive(entry.id()) ? "" : " · Out"), TEXT));
            actions.add(dialogs.button(player, label, null, true, 150, (p, v) -> {
                manager().requireCurrent(id);
                if (game.terminal() || !game.alive(entry.id())) { teams(p, id, selected); return; }
                menu(p, "Withdraw Player", DialogText.muted("Remove " + entry.name() + " from this Manhunt?"), List.of(
                        action(p, DialogIcon.CLOSE, "Withdraw " + entry.name(), DANGER, target -> { manager().requireCurrent(id); manager().withdraw(entry.id()); open(target); })), target -> teams(target, id, selected));
            }));
        }
        Dialogs.navigationRow(actions, selected > 0 ? button(player, DialogIcon.BACK, "Previous", MUTED, 150, (p, v) -> teams(p, id, selected - 1)) : null,
                selected + 1 < pages ? button(player, DialogIcon.NEXT, "Next", ACCENT, 150, (p, v) -> teams(p, id, selected + 1)) : null, 150);
        dialogs.show(player, "Manhunt Teams", List.of(DialogBody.plainMessage(DialogText.muted("Choose a player to withdraw them from this game."), 320)), List.of(), actions, 2, 150,
                button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> open(p)));
    }
    private String kitName(String id) { var kit = plugin.getKitManager().getKit(id); return kit == null ? id : kit.getDisplayName(); }
    private static String rootMessage(Throwable error) { while (error.getCause() != null) error = error.getCause(); return error.getMessage() == null ? "Unknown error" : error.getMessage(); }
}
