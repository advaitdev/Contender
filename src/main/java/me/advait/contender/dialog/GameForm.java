package me.advait.contender.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.BooleanDialogInput;
import io.papermc.paper.registry.data.dialog.input.NumberRangeDialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import me.advait.contender.Contender;
import me.advait.contender.kit.Kit;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.player.PlayerIdentityResolver;
import me.advait.contender.tournament.RosterParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static me.advait.contender.dialog.DialogPalette.*;

/** The one-screen "new minigame" form: name, map, kit, game options, and the roster. */
public final class GameForm {
    private static final int INPUT = 300, NAV = 150;

    /** What the director filled in. {@code options} reads the game's own inputs. */
    public record Result(String name, ArenaMap map, Kit kit, Map<UUID, String> roster, Map<String, String> values) { }

    /**
     * @param needsMap show the map picker (maps with both spawns)
     * @param needsKit show the kit picker; {@code kitOptional} adds a "Default" choice
     */
    public record Spec(String title, String defaultName, String hint, boolean needsMap, boolean needsKit, boolean kitOptional,
                       List<DialogInput> extras, int minimumPlayers, String noKitLabel) {
        public Spec(String title, String defaultName, String hint, boolean needsMap, boolean needsKit, boolean kitOptional,
                    List<DialogInput> extras, int minimumPlayers) {
            this(title, defaultName, hint, needsMap, needsKit, kitOptional, extras, minimumPlayers, "Default kit");
        }
    }

    private final Contender plugin;
    private final Dialogs dialogs;

    public GameForm(Contender plugin) {
        this.plugin = plugin;
        this.dialogs = new Dialogs(plugin);
    }

    public void open(Player player, Spec spec, BiConsumer<Player, Result> create, Consumer<Player> back) {
        open(player, spec, create, back, null, null);
    }

    private void open(Player player, Spec spec, BiConsumer<Player, Result> create, Consumer<Player> back, Map<String, String> draft, String error) {
        List<ArenaMap> maps = plugin.getMapManager().getMaps().stream().filter(ArenaMap::isComplete).toList();
        List<Kit> kits = new ArrayList<>(plugin.getKitManager().getKits());
        if (spec.needsMap() && maps.isEmpty() || spec.needsKit() && !spec.kitOptional() && kits.isEmpty()) {
            dialogs.show(player, "Before You Begin", List.of(DialogBody.plainMessage(new SetupRequirements(maps.size(), kits.size()).body(), 320)), List.of(), List.of(
                    dialogs.button(player, DialogIcon.MAP.label("Maps", TEXT), null, true, INPUT, (p, view) -> new ArenaDialogs(plugin, q -> open(q, spec, create, back)).open(p)),
                    dialogs.button(player, DialogIcon.DUEL.label("Kits", TEXT), null, true, INPUT, (p, view) -> new KitDialogs(plugin).open(p, q -> open(q, spec, create, back)))),
                    1, NAV, dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> back.accept(p)));
            return;
        }
        Map<String, String> values = draft == null ? new HashMap<>() : draft;
        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.text("name", DialogIcon.NAME.label("Name")).initial(values.getOrDefault("name", spec.defaultName())).maxLength(48).width(INPUT).build());
        if (spec.needsMap()) {
            String selected = values.getOrDefault("map", maps.getFirst().getId());
            inputs.add(DialogInput.singleOption("map", DialogIcon.MAP.label("Map"), maps.stream().map(map -> Dialogs.option(map.getId(),
                    map.getDisplayName() + " (" + plugin.getArenas().ready(map.getId()) + " ready)", map.getId().equals(selected))).toList()).width(INPUT).build());
        }
        if (spec.needsKit()) {
            String selected = values.getOrDefault("kit", spec.kitOptional() ? "" : kits.getFirst().getId());
            List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>();
            if (spec.kitOptional()) options.add(Dialogs.option("", spec.noKitLabel(), selected.isEmpty()));
            for (Kit kit : kits) options.add(SingleOptionDialogInput.OptionEntry.create(kit.getId(), KitIcons.label(kit), kit.getId().equals(selected)));
            inputs.add(DialogInput.singleOption("kit", text("Kit", TEXT), options).width(INPUT).build());
        }
        // Coming back to the form (after an error) keeps what the director had set.
        for (DialogInput extra : spec.extras()) inputs.add(withDraft(extra, values.get(extra.key())));
        String roster = values.getOrDefault("players", String.join(", ", Bukkit.getOnlinePlayers().stream()
                .filter(p -> plugin.getRoleManager().isContestant(p.getUniqueId())).map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList()));
        // One line keeps Back / Create on screen at 1280x720; the field scrolls for long rosters.
        inputs.add(DialogInput.text("players", DialogIcon.PLAYERS.label("Players (separate with commas)")).initial(roster).maxLength(4096)
                .width(INPUT).build());
        List<DialogBody> body = new ArrayList<>();
        if (error != null) body.add(DialogBody.plainMessage(text(error, DANGER), 320));
        body.add(DialogBody.plainMessage(DialogText.muted(spec.hint()), 320));
        List<ActionButton> buttons = new ArrayList<>();
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> back.accept(p)),
                dialogs.button(player, DialogIcon.SAVE.label("Create", ACCENT), DialogText.muted("Saves the game. Start it from /tournament when everyone is ready."), true, NAV,
                        (p, view) -> submit(p, spec, create, back, read(view, spec))), NAV);
        dialogs.show(player, spec.title(), body, inputs, buttons, 2, NAV, null);
    }

    private static Map<String, String> read(DialogResponseView view, Spec spec) {
        Map<String, String> values = new HashMap<>();
        values.put("name", Objects.requireNonNullElse(view.getText("name"), "").strip());
        values.put("players", Objects.requireNonNullElse(view.getText("players"), ""));
        if (spec.needsMap()) values.put("map", Objects.requireNonNullElse(view.getText("map"), ""));
        if (spec.needsKit()) values.put("kit", Objects.requireNonNullElse(view.getText("kit"), ""));
        for (DialogInput input : spec.extras()) {
            String key = input.key();
            // Read by the input's type: asked for text, a slider answers "3.0", which isn't a whole number.
            String text;
            if (input instanceof NumberRangeDialogInput) {
                Float number = view.getFloat(key);
                text = number == null ? null : Integer.toString(Math.round(number));
            } else if (input instanceof BooleanDialogInput) {
                Boolean bool = view.getBoolean(key);
                text = bool == null ? null : bool.toString();
            } else {
                text = view.getText(key);
            }
            values.put(key, text == null ? "" : text);
        }
        return values;
    }

    /** The same input, starting at a value the director already chose. */
    private static DialogInput withDraft(DialogInput input, String value) {
        if (value == null || value.isEmpty() && !(input instanceof TextDialogInput)) return input;
        try {
            if (input instanceof NumberRangeDialogInput number) {
                var builder = DialogInput.numberRange(number.key(), number.label(), number.start(), number.end()).width(number.width())
                        .initial(Math.clamp(Float.parseFloat(value), number.start(), number.end()));
                if (number.step() != null) builder.step(number.step());
                if (number.labelFormat() != null) builder.labelFormat(number.labelFormat());
                return builder.build();
            }
            if (input instanceof TextDialogInput text) {
                var builder = DialogInput.text(text.key(), text.label()).width(text.width()).labelVisible(text.labelVisible())
                        .initial(value).maxLength(text.maxLength());
                if (text.multiline() != null) builder.multiline(text.multiline());
                return builder.build();
            }
            if (input instanceof BooleanDialogInput bool) {
                return DialogInput.bool(bool.key(), bool.label()).initial(Boolean.parseBoolean(value)).onTrue(bool.onTrue()).onFalse(bool.onFalse()).build();
            }
            if (input instanceof SingleOptionDialogInput single) {
                List<SingleOptionDialogInput.OptionEntry> entries = single.entries().stream()
                        .map(entry -> SingleOptionDialogInput.OptionEntry.create(entry.id(), entry.display(), entry.id().equals(value))).toList();
                return DialogInput.singleOption(single.key(), single.label(), entries).width(single.width()).labelVisible(single.labelVisible()).build();
            }
        } catch (RuntimeException unreadable) {
            return input;
        }
        return input;
    }

    private void submit(Player player, Spec spec, BiConsumer<Player, Result> create, Consumer<Player> back, Map<String, String> values) {
        if (values.get("name").isBlank()) { open(player, spec, create, back, values, "Give the game a name."); return; }
        ArenaMap map = spec.needsMap() ? plugin.getMapManager().getMap(values.get("map")) : null;
        if (spec.needsMap() && (map == null || !map.isComplete())) { open(player, spec, create, back, values, "Choose a map with both spawns set."); return; }
        Kit kit = spec.needsKit() && !values.get("kit").isEmpty() ? plugin.getKitManager().getKit(values.get("kit")) : null;
        if (spec.needsKit() && !spec.kitOptional() && kit == null) { open(player, spec, create, back, values, "Choose a kit."); return; }
        String text = values.get("players").strip();
        List<String> names = Arrays.stream(text.split("[,\\s]+")).filter(s -> !s.isBlank()).toList();
        if (names.size() < spec.minimumPlayers()) { open(player, spec, create, back, values, "Enter at least " + spec.minimumPlayers() + (spec.minimumPlayers() == 1 ? " player." : " players.")); return; }
        if (names.size() > 64) { open(player, spec, create, back, values, "Enter at most 64 players."); return; }
        player.closeDialog();
        Dialogs.tell(player, "Checking the player names…");
        var lookups = new LinkedHashMap<String, java.util.concurrent.CompletableFuture<RosterParser.PlayerIdentity>>();
        try {
            for (String name : names) lookups.putIfAbsent(name.toLowerCase(Locale.ROOT), PlayerIdentityResolver.resolve(name));
        } catch (RuntimeException failure) { open(player, spec, create, back, values, failure.getMessage()); return; }
        java.util.concurrent.CompletableFuture.allOf(lookups.values().toArray(java.util.concurrent.CompletableFuture[]::new)).whenComplete((ignored, failure) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (failure != null) { open(player, spec, create, back, values, Dialogs.message(failure)); return; }
                    Map<UUID, String> roster = new LinkedHashMap<>();
                    for (var lookup : lookups.values()) {
                        var identity = lookup.join();
                        if (!plugin.getRoleManager().isContestant(identity.id())) {
                            open(player, spec, create, back, values, identity.name() + " is not a contestant."); return;
                        }
                        roster.put(identity.id(), identity.name());
                    }
                    try { create.accept(player, new Result(values.get("name"), map, kit, roster, Map.copyOf(values))); }
                    catch (RuntimeException error) { open(player, spec, create, back, values, Dialogs.message(error)); }
                }));
    }

    public static DialogInput number(String key, DialogIcon icon, String label, int min, int max, int initial, int step, String format) {
        var builder = DialogInput.numberRange(key, icon.label(label), min, max).initial((float) initial).step((float) step).width(INPUT);
        if (format != null) builder.labelFormat(format);
        return builder.build();
    }

    public static int intOption(Map<String, String> options, String key, int min, int max, int fallback) {
        try { return Math.clamp(Integer.parseInt(options.get(key)), min, max); }
        catch (RuntimeException invalid) { return fallback; }
    }
}
