package me.advait.contender.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryBuilderFactory;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.DialogInstancesProvider;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.MultiActionType;
import io.papermc.paper.registry.data.dialog.type.NoticeType;
import io.papermc.paper.registry.data.dialog.input.NumberRangeDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import me.advait.contender.arena.ArenaManager;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.duel.DuelSetup;
import me.advait.contender.kit.Kit;
import me.advait.contender.kit.KitManager;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.MapManager;
import me.advait.contender.testutil.StateTestServer;
import me.advait.contender.tournament.TournamentManager;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DialogSubmissionTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path settingsFolder;
    private final StateTestServer server = new StateTestServer();
    private final Player player = mock(Player.class);
    private final ArenaManager arenas = mock(ArenaManager.class);
    private final DialogInstancesProvider provider = mock(DialogInstancesProvider.class);
    private final List<String> inputKeys = new ArrayList<>();
    private final Map<String, String> initialText = new HashMap<>();
    private final Map<String, Float> initialNumbers = new HashMap<>();
    private final List<DialogActionCallback> submissions = new ArrayList<>();
    private final List<ClickCallback<Audience>> staticCallbacks = new ArrayList<>();
    private final List<String> buttonLabels = new ArrayList<>();
    private final Map<String, DialogActionCallback> actions = new HashMap<>();
    private final Map<DialogAction, DialogActionCallback> callbackByAction = new java.util.IdentityHashMap<>();
    private final List<String> messages = new ArrayList<>();
    private Component shownTitle;
    private List<DialogBody> shownBody;
    private List<DialogInput> shownInputs;
    private List<ActionButton> shownButtons;
    private ActionButton shownFooter;
    private int shownColumns;
    private MockedStatic<DialogInstancesProvider> providers;
    private MockedStatic<RegistryAccess> registries;
    private MockedStatic<Dialog> dialogs;
    private MockedStatic<ClickEvent> clicks;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach void setUp() {
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("contender.master")).thenReturn(true);
        when(server.plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(server.plugin.getArenaManager()).thenReturn(arenas);

        // Replace only the server-owned factories; exercise Contender's real form and callbacks.
        providers = mockStatic(DialogInstancesProvider.class);
        providers.when(DialogInstancesProvider::instance).thenReturn(provider);
        registries = mockStatic(RegistryAccess.class);
        var access = mock(RegistryAccess.class, RETURNS_MOCKS);
        registries.when(RegistryAccess::registryAccess).thenReturn(access);
        doReturn(mock(Registry.class)).when(access).getRegistry(RegistryKey.DIALOG);
        dialogs = mockStatic(Dialog.class);
        dialogs.when(() -> Dialog.create(any())).thenAnswer(call -> {
            RegistryBuilderFactory<Dialog, DialogRegistryEntry.Builder> factory = mock(RegistryBuilderFactory.class);
            when(factory.empty()).thenReturn(mock(DialogRegistryEntry.Builder.class, RETURNS_SELF));
            Consumer<RegistryBuilderFactory<Dialog, DialogRegistryEntry.Builder>> build = call.getArgument(0);
            build.accept(factory);
            return mock(Dialog.class);
        });
        when(provider.dialogBaseBuilder(any())).thenAnswer(call -> {
            shownTitle = call.getArgument(0);
            var builder = mock(DialogBase.Builder.class, RETURNS_SELF);
            when(builder.body(anyList())).thenAnswer(body -> { shownBody = List.copyOf(body.getArgument(0)); return builder; });
            when(builder.inputs(anyList())).thenAnswer(inputs -> { shownInputs = List.copyOf(inputs.getArgument(0)); return builder; });
            when(builder.build()).thenReturn(mock(DialogBase.class));
            return builder;
        });
        when(provider.multiAction(anyList())).thenAnswer(call -> {
            shownButtons = List.copyOf(call.getArgument(0));
            var builder = mock(MultiActionType.Builder.class, RETURNS_SELF);
            when(builder.columns(anyInt())).thenAnswer(columns -> { shownColumns = columns.getArgument(0); return builder; });
            when(builder.exitAction(any())).thenAnswer(footer -> { shownFooter = footer.getArgument(0); return builder; });
            when(builder.build()).thenReturn(mock(MultiActionType.class));
            return builder;
        });
        when(provider.notice(any(ActionButton.class))).thenAnswer(call -> {
            shownFooter = call.getArgument(0);
            shownButtons = List.of(shownFooter);
            shownColumns = 1;
            return mock(NoticeType.class);
        });
        clicks = mockStatic(ClickEvent.class);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        clicks.when(() -> ClickEvent.callback(any(), any(ClickCallback.Options.class))).thenAnswer(call -> {
            staticCallbacks.add(call.getArgument(0));
            return mock(ClickEvent.class);
        });
        when(provider.staticAction(any())).thenReturn(mock(DialogAction.StaticAction.class));
        when(provider.register(any(), any())).thenAnswer(call -> {
            submissions.add(call.getArgument(0));
            actions.put(buttonLabels.getLast(), call.getArgument(0));
            var action = mock(DialogAction.CustomClickAction.class);
            callbackByAction.put(action, call.getArgument(0));
            return action;
        });
        when(provider.actionButtonBuilder(any())).thenAnswer(call -> {
            Component label = call.getArgument(0);
            Component text = label.children().isEmpty() ? label : label.children().getLast();
            buttonLabels.add(PlainTextComponentSerializer.plainText().serialize(text).strip());
            var builder = mock(ActionButton.Builder.class, RETURNS_SELF);
            var button = mock(ActionButton.class);
            when(button.label()).thenReturn(label);
            when(builder.action(any())).thenAnswer(action -> { when(button.action()).thenReturn(action.getArgument(0)); return builder; });
            when(builder.width(anyInt())).thenAnswer(width -> { when(button.width()).thenReturn(width.getArgument(0)); return builder; });
            when(builder.build()).thenReturn(button);
            return builder;
        });
        when(provider.plainMessageDialogBody(any())).thenAnswer(call -> plainBody(call.getArgument(0), 200));
        when(provider.plainMessageDialogBody(any(), anyInt())).thenAnswer(call -> plainBody(call.getArgument(0), call.getArgument(1)));
        when(provider.textBuilder(anyString(), any())).thenAnswer(call -> {
            String key = call.getArgument(0);
            inputKeys.add(key);
            var input = mock(TextDialogInput.class);
            when(input.key()).thenReturn(key);
            var builder = mock(TextDialogInput.Builder.class, RETURNS_SELF);
            when(input.label()).thenReturn(call.getArgument(1));
            when(builder.width(anyInt())).thenAnswer(width -> { when(input.width()).thenReturn(width.getArgument(0)); return builder; });
            when(builder.initial(anyString())).thenAnswer(initial -> { initialText.put(key, initial.getArgument(0)); return builder; });
            when(builder.build()).thenReturn(input);
            return builder;
        });
        when(provider.singleOptionEntry(anyString(), any(), anyBoolean())).thenReturn(mock(io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.OptionEntry.class));
        when(provider.singleOptionBuilder(anyString(), any(), anyList())).thenAnswer(call -> {
            String key = call.getArgument(0); inputKeys.add(key);
            var input = mock(io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.class); when(input.key()).thenReturn(key);
            var builder = mock(io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.Builder.class, RETURNS_SELF);
            when(input.label()).thenReturn(call.getArgument(1));
            when(builder.width(anyInt())).thenAnswer(width -> { when(input.width()).thenReturn(width.getArgument(0)); return builder; });
            when(builder.build()).thenReturn(input); return builder;
        });
        when(provider.numberRangeBuilder(anyString(), any(), anyFloat(), anyFloat())).thenAnswer(call -> {
            String key = call.getArgument(0);
            inputKeys.add(key);
            var input = mock(NumberRangeDialogInput.class);
            when(input.key()).thenReturn(key);
            when(input.start()).thenReturn(call.getArgument(2));
            when(input.end()).thenReturn(call.getArgument(3));
            var builder = mock(NumberRangeDialogInput.Builder.class, RETURNS_SELF);
            when(input.label()).thenReturn(call.getArgument(1));
            when(builder.initial(anyFloat())).thenAnswer(initial -> { initialNumbers.put(key, initial.getArgument(0)); return builder; });
            when(builder.width(anyInt())).thenAnswer(width -> { when(input.width()).thenReturn(width.getArgument(0)); return builder; });
            when(builder.step(anyFloat())).thenAnswer(step -> { when(input.step()).thenReturn(step.getArgument(0)); return builder; });
            when(builder.labelFormat(anyString())).thenAnswer(format -> { when(input.labelFormat()).thenReturn(format.getArgument(0)); return builder; });
            when(builder.build()).thenReturn(input);
            return builder;
        });
    }

    private PlainMessageDialogBody plainBody(Component contents, int width) {
        messages.add(PlainTextComponentSerializer.plainText().serialize(contents));
        var body = mock(PlainMessageDialogBody.class);
        when(body.contents()).thenReturn(contents);
        when(body.width()).thenReturn(width);
        return body;
    }

    @AfterEach void tearDown() {
        if (bukkit != null) bukkit.close();
        if (clicks != null) clicks.close();
        if (dialogs != null) dialogs.close();
        if (registries != null) registries.close();
        if (providers != null) providers.close();
        server.close();
    }

    @Test void bracketShowsAPublicMinigameLeaderboardWithACompletePartialColumn() {
        when(player.hasPermission("contender.master")).thenReturn(false);
        var tab = mock(me.advait.contender.tab.TabManager.class); when(server.plugin.getTabManager()).thenReturn(tab);
        when(tab.event()).thenReturn(new me.advait.contender.tab.TabManager.Event(UUID.randomUUID(), "Combo Stage", "Playing", "Leaderboard", true, false));
        var rows = List.of(me.advait.contender.tab.TabRow.label(Component.text("Combo")),
                me.advait.contender.tab.TabRow.label(Component.text("-----")),
                new me.advait.contender.tab.TabRow(Component.text("Alice ∞"), -1, "skin", "signed", Component.text("Head Alice ∞"), null));
        when(tab.layout(player)).thenReturn(new me.advait.contender.tab.BracketLayout.Layout(rows, 1, 0, 1, true));

        new BracketDialogs(server.plugin).open(player);

        assertEquals("Leaderboard", plain(shownTitle));
        assertTrue(messages.stream().anyMatch(message -> message.contains("Head Alice ∞ X")));
        assertEquals(List.of("Refresh"), shownButtons.stream().map(button -> plain(button.label())).toList());
        assertEquals(1, shownColumns); assertEquals(300, shownButtons.getFirst().width());
        assertTrue(shownInputs.isEmpty());
        actions.get("Refresh").accept(mock(DialogResponseView.class), player);
        assertEquals("Leaderboard", plain(shownTitle));
    }

    @Test void maceRaceWizardHasPairedNavigationAndUsesTheCourseDefaults() {
        var kits = mock(KitManager.class); when(server.plugin.getKitManager()).thenReturn(kits);
        var races = mock(me.advait.contender.race.RaceManager.class);
        when(server.plugin.getRaceManager()).thenReturn(races);
        var maps = mock(MapManager.class); when(server.plugin.getMapManager()).thenReturn(maps);
        var map = new ArenaMap("race"); map.setDisplayName("Sky Course"); when(maps.getMap("race")).thenReturn(map);
        var course = me.advait.contender.race.RaceCourse.defaults("race");
        when(races.courses()).thenReturn(List.of(course)); when(races.course("race")).thenReturn(course);
        var roles = mock(me.advait.contender.role.RoleManager.class); when(server.plugin.getRoleManager()).thenReturn(roles);
        var menu = new RaceDialogs(server.plugin); menu.create(player);
        assertEquals(2, shownColumns); assertEquals(List.of("Back", "Next: Players"), shownButtons.stream().map(b -> plain(b.label())).toList());
        assertTrue(shownButtons.stream().allMatch(b -> b.width() == 150));
        var response = mock(DialogResponseView.class); when(response.getText("name")).thenReturn("Mace Stage"); when(response.getText("course")).thenReturn("race");
        when(response.getText("kit")).thenReturn("@default");
        actions.get("Next: Players").accept(response, player);
        assertEquals(List.of("Back", "Next: Rules"), shownButtons.stream().map(b -> plain(b.label())).toList());
        when(response.getText("players")).thenReturn("Alice, Bob"); actions.get("Next: Rules").accept(response, player);
        assertEquals("15", initialText.get("advance")); assertEquals("0", initialText.get("minutes"));
        assertTrue(shownInputs.stream().allMatch(i -> ((TextDialogInput) i).width() == 300));
        assertTrue(messages.stream().anyMatch(m -> m.contains("#1 → #16")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("Return on Ground: Enabled")));
        verify(races, never()).create(anyString(), anyString(), anyInt(), anyInt(), anyList());
    }

    @Test void maceRaceKitSelectionUsesItsSpriteAndSurvivesBackNavigation() {
        var races = mock(me.advait.contender.race.RaceManager.class);
        when(server.plugin.getRaceManager()).thenReturn(races);
        var maps = mock(MapManager.class); when(server.plugin.getMapManager()).thenReturn(maps);
        var map = new ArenaMap("race"); map.setDisplayName("Sky Course"); when(maps.getMap("race")).thenReturn(map);
        var course = me.advait.contender.race.RaceCourse.defaults("race");
        when(races.courses()).thenReturn(List.of(course)); when(races.course("race")).thenReturn(course);
        var roles = mock(me.advait.contender.role.RoleManager.class); when(server.plugin.getRoleManager()).thenReturn(roles);
        var kits = mock(KitManager.class); when(server.plugin.getKitManager()).thenReturn(kits);
        var kit = new Kit("flight"); kit.setDisplayName("Flight Kit"); kit.setIcon(org.bukkit.Material.FEATHER);
        when(kits.getKits()).thenReturn(List.of(kit)); when(kits.getKit("flight")).thenReturn(kit);

        new RaceDialogs(server.plugin).create(player);
        verify(provider).singleOptionEntry(eq("flight"), eq(KitIcons.label(kit)), eq(false));
        var response = mock(DialogResponseView.class);
        when(response.getText("name")).thenReturn("Mace Stage"); when(response.getText("course")).thenReturn("race");
        when(response.getText("kit")).thenReturn("flight"); when(response.getText("players")).thenReturn("Alice, Bob");
        actions.get("Next: Players").accept(response, player);
        actions.get("Next: Rules").accept(response, player);
        when(response.getText("advance")).thenReturn("15"); when(response.getText("minutes")).thenReturn("0");
        actions.get("Back").accept(response, player);
        actions.get("Back").accept(response, player);

        verify(provider).singleOptionEntry(eq("flight"), eq(KitIcons.label(kit)), eq(true));
        assertEquals("Mace Stage", initialText.get("name"));
        assertEquals(List.of("Back", "Next: Players"), shownButtons.stream().map(b -> plain(b.label())).toList());
    }

    @Test void raceCourseProvidesAMapSpecificRemovalTool() {
        var races = mock(me.advait.contender.race.RaceManager.class);
        when(server.plugin.getRaceManager()).thenReturn(races);
        var maps = mock(MapManager.class); when(server.plugin.getMapManager()).thenReturn(maps);
        var map = new ArenaMap("race"); map.setDisplayName("Sky Course");
        when(maps.getMap("race")).thenReturn(map); when(maps.getMaps()).thenReturn(List.of(map));
        when(races.course("race")).thenReturn(me.advait.contender.race.RaceCourse.defaults("race"));
        new RaceDialogs(server.plugin).courses(player, 0);
        actions.get("Sky Course").accept(mock(DialogResponseView.class), player);

        assertEquals(1, shownColumns);
        assertTrue(messages.stream().anyMatch(message -> message.contains("Remaining checkpoints renumber automatically")));
        ActionButton tool = shownButtons.stream().filter(button -> plain(button.label()).equals("Get Removal Tool")).findFirst().orElseThrow();
        assertEquals(300, tool.width());
        assertInstanceOf(ObjectComponent.class, tool.label().children().getFirst());
        assertRegular(tool.label());
        click(tool);
        verify(races).giveRemovalTool(player, "race");
    }

    @Test void maceRaceCourseRulesSaveGroundReturnWithPairedNavigation() {
        var races = mock(me.advait.contender.race.RaceManager.class);
        when(server.plugin.getRaceManager()).thenReturn(races);
        var maps = mock(MapManager.class); when(server.plugin.getMapManager()).thenReturn(maps);
        var map = new ArenaMap("race"); map.setDisplayName("Sky Course");
        when(maps.getMap("race")).thenReturn(map); when(maps.getMaps()).thenReturn(List.of(map));
        when(races.course("race")).thenReturn(me.advait.contender.race.RaceCourse.defaults("race"));

        new RaceDialogs(server.plugin).courses(player, 0);
        actions.get("Sky Course").accept(mock(DialogResponseView.class), player);
        actions.get("Course Rules").accept(mock(DialogResponseView.class), player);

        assertEquals(List.of("advance", "finish", "height", "return_on_ground"), shownInputs.stream().map(DialogInput::key).toList());
        var input = (io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput) shownInputs.getLast();
        assertEquals(300, input.width());
        assertEquals("Return on Ground", plain(input.label()));
        assertInstanceOf(ObjectComponent.class, input.label().children().getFirst());
        assertEquals(List.of("Back", "Save Rules"), shownButtons.stream().map(b -> plain(b.label())).toList());
        assertTrue(shownButtons.stream().allMatch(b -> b.width() == 150));
        assertEquals(2, shownColumns);
        assertTrue(messages.stream().anyMatch(m -> m.contains("until you first leave the ground")));

        var response = mock(DialogResponseView.class);
        when(response.getText("advance")).thenReturn("15");
        when(response.getText("finish")).thenReturn("Finish");
        when(response.getText("height")).thenReturn("3");
        when(response.getText("return_on_ground")).thenReturn("false");
        actions.get("Save Rules").accept(response, player);

        verify(races).configure(new me.advait.contender.race.RaceCourse("race", 15, "Finish", 3, false));
    }

    @Test void hackerSlidersApplyOnCloseAndIgnoreForeignOrRepeatedCallbacks() {
        var manager = hackerManager();
        UUID selection = manager.profile(player.getUniqueId()).selection();
        var menu = new HackerDialogs(server.plugin, manager); menu.open(player);
        assertEquals("Hacks", plain(shownTitle));
        assertEquals(1, shownColumns);
        assertEquals(List.of("Close"), shownButtons.stream().map(b -> plain(b.label())).toList());
        assertEquals(150, shownFooter.width());
        assertEquals("Close", ((TextComponent) shownFooter.label()).content()); // No Close icon.
        assertEquals(8, shownInputs.size());
        for (var setting : me.advait.contender.hacker.HackSetting.values()) {
            var input = (NumberRangeDialogInput) shownInputs.stream().filter(i -> i.key().equals(setting.key())).findFirst().orElseThrow();
            assertEquals(300, input.width());
            assertEquals((float) setting.min, input.start());
            assertEquals((float) setting.max, input.end());
            assertEquals((float) setting.normal, initialNumbers.get(setting.key()));
            assertTrue(input.step() > 0);
            assertTrue(plain(input.label()).contains("[" + setting.display(setting.warning) + "]"));
            assertInstanceOf(ObjectComponent.class, input.label().children().getFirst());
            assertRegular(input.label());
        }
        assertEquals("%s: %s%%", ((NumberRangeDialogInput) shownInputs.get(3)).labelFormat());
        assertTrue(messages.stream().anyMatch(text -> text.contains("may look blatant")));
        assertTrue(messages.stream().anyMatch(text -> text.contains("press Escape")));
        assertTrue(messages.contains("Hacks stay active everywhere, including the lobby."));
        assertFalse(messages.stream().anyMatch(text -> text.contains("Inactive") || text.contains("when you start playing")));
        assertFalse(actions.keySet().stream().anyMatch(label -> label.contains("Save") || label.contains("Review") || label.contains("Next")));
        verify(manager, never()).update(any(), any(), any());

        var response = hackerSliders();
        when(response.getFloat("reach")).thenReturn(6f);
        when(response.getFloat("anti_knockback")).thenReturn(100f);
        var close = callbackByAction.get(shownFooter.action()); // Notice uses this same action for Escape.
        Player stranger = mock(Player.class); when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
        close.accept(response, stranger);
        verify(manager, never()).update(any(), any(), any());
        close.accept(response, player);
        verify(manager).update(eq(player), eq(selection), argThat(settings ->
                settings.get(me.advait.contender.hacker.HackSetting.REACH) == 6
                && settings.get(me.advait.contender.hacker.HackSetting.ANTI_KNOCKBACK) == 100
                && settings.get(me.advait.contender.hacker.HackSetting.STEP_HEIGHT) == .6));
        verify(player).closeDialog();
        verify(player).sendMessage(argThat((Component message) -> plain(message).equals("Hacks applied.")));
        close.accept(response, player);
        verify(manager, times(1)).update(any(), any(), any());
    }

    @Test void nonHackersCannotOpenOrUseAnOldHackerFormEvenWithAdminPermissions() {
        var manager = mock(me.advait.contender.hacker.HackerManager.class);
        var menu = new HackerDialogs(server.plugin, manager); menu.open(player);
        verify(player, never()).showDialog(any());
        when(manager.isHacker(player.getUniqueId())).thenReturn(true);
        when(manager.profile(player.getUniqueId())).thenReturn(new me.advait.contender.hacker.HackerManager.Profile("Alice",
                me.advait.contender.hacker.HackSettings.defaults(), UUID.randomUUID(), false));
        menu.open(player); var close = actions.get("Close");
        when(manager.isHacker(player.getUniqueId())).thenReturn(false);
        var response = mock(DialogResponseView.class); close.accept(response, player);
        verify(response, never()).getFloat(anyString()); verify(manager, never()).update(any(), any(), any());
    }

    @Test void hackerCloseValidatesEverySliderBeforeApplyingAndCanRetry() {
        var manager = hackerManager();
        new HackerDialogs(server.plugin, manager).open(player);
        var close = actions.get("Close");
        var response = hackerSliders();
        for (Float invalid : new Float[]{null, Float.NaN, Float.POSITIVE_INFINITY, .5f, 4f}) {
            when(response.getFloat("step_height")).thenReturn(invalid);
            close.accept(response, player);
            verify(manager, never()).update(any(), any(), any());
        }
        verify(player, never()).closeDialog();
        when(response.getFloat("step_height")).thenReturn(.6f);
        close.accept(response, player);
        verify(manager).update(eq(player), any(), any());
        verify(player).closeDialog();
    }

    @Test void reopenedReselectedAndClearedHackerMenusCannotApplyStaleSliders() {
        var manager = hackerManager();
        var menu = new HackerDialogs(server.plugin, manager);
        menu.open(player);
        var oldClose = actions.get("Close");
        menu.open(player);
        var response = hackerSliders();
        oldClose.accept(response, player);
        verify(manager, never()).update(any(), any(), any());

        var selectionClose = actions.get("Close");
        when(manager.profile(player.getUniqueId())).thenReturn(new me.advait.contender.hacker.HackerManager.Profile("Alice",
                me.advait.contender.hacker.HackSettings.defaults(), UUID.randomUUID(), false));
        selectionClose.accept(response, player);
        verify(manager, never()).update(any(), any(), any());

        menu.open(player);
        var clearedClose = actions.get("Close");
        menu.clear();
        clearedClose.accept(response, player);
        verify(manager, never()).update(any(), any(), any());
    }

    private me.advait.contender.hacker.HackerManager hackerManager() {
        var manager = mock(me.advait.contender.hacker.HackerManager.class);
        when(manager.isHacker(player.getUniqueId())).thenReturn(true);
        when(manager.profile(player.getUniqueId())).thenReturn(new me.advait.contender.hacker.HackerManager.Profile("Alice",
                me.advait.contender.hacker.HackSettings.defaults(), UUID.randomUUID(), false));
        return manager;
    }

    private DialogResponseView hackerSliders() {
        var response = mock(DialogResponseView.class);
        for (var setting : me.advait.contender.hacker.HackSetting.values())
            when(response.getFloat(setting.key())).thenReturn((float) setting.normal);
        return response;
    }

    @Test void mapFormSubmitsMinesWithoutOverwritingPapersCallbackId() {
        new ArenaDialogs(server.plugin).create(player);
        assertFalse(inputKeys.contains("id"), "Paper uses id for the callback UUID");
        var response = mock(DialogResponseView.class);
        when(response.getText(inputKeys.get(0))).thenReturn("mines");
        when(response.getText("name")).thenReturn("The Mines");
        when(response.getFloat("copies")).thenReturn(20f);
        when(arenas.create(player, "mines", "The Mines", 20)).thenReturn(new CompletableFuture<>());

        submissions.getFirst().accept(response, player);

        verify(arenas).create(player, "mines", "The Mines", 20);
        verify(player).closeDialog();
    }

    @Test void arenaStatusCanBeCheckedDuringPreparationWithoutSavingTheSource() {
        ArenaMap map = arenaMap();
        var preparation = new CompletableFuture<Void>();
        when(arenas.prepare(map)).thenReturn(preparation);
        when(arenas.readiness("mines")).thenReturn("0 / 20 ready. Preparing 20 copies automatically.");
        var menu = new ArenaDialogs(server.plugin);

        menu.edit(player, "mines");

        assertTrue(messages.getLast().contains("Preparing 20 copies automatically."));
        assertTrue(messages.getLast().contains("Copies prepare automatically and reset after use."));
        assertEquals(List.of("---", "Back", "Check Arena Copies"), shownButtons.subList(5, 8).stream()
                .map(button -> plain(button.label())).toList());
        assertTrue(shownButtons.size() % 2 == 0);
        shownButtons.forEach(button -> assertRegular(button.label()));
        actions.get("Check Arena Copies").accept(mock(DialogResponseView.class), player);

        when(arenas.available("mines")).thenReturn(3);
        when(arenas.readiness("mines")).thenReturn("3 / 20 ready. Preparing 17 copies automatically.");
        actions.get("Check Arena Copies").accept(mock(DialogResponseView.class), player);

        assertTrue(messages.getLast().contains("3 / 20 ready. Preparing 17 copies automatically."));
        verify(arenas, times(2)).prepare(map);
        verify(arenas, never()).saveBlocks(any());
        verify(player, never()).closeDialog();
        preparation.complete(null);
        verify(player, never()).sendMessage(any(Component.class));
    }

    @Test void arenaCheckReportsThePreparationFailureWithoutClaimingCopiesAreReady() {
        ArenaMap map = arenaMap();
        var preparation = new CompletableFuture<Void>();
        when(arenas.prepare(map)).thenReturn(preparation);
        when(arenas.readiness("mines")).thenReturn("0 / 20 ready. Preparing 20 copies automatically.");
        new ArenaDialogs(server.plugin).edit(player, "mines");

        actions.get("Check Arena Copies").accept(mock(DialogResponseView.class), player);
        preparation.completeExceptionally(new IllegalStateException("The saved template could not be read."));

        verify(player).sendMessage(argThat((Component message) -> plain(message).equals("The saved template could not be read.")));
        verify(player, times(1)).sendMessage(any(Component.class));
    }

    @Test void savingArenaBlocksIsExplicitAndExplainsAutomaticUpdating() {
        ArenaMap map = arenaMap();
        var saving = new CompletableFuture<Void>();
        when(arenas.saveBlocks(map)).thenReturn(saving);
        when(arenas.readiness("mines")).thenReturn("20 / 20 ready.");
        when(arenas.available("mines")).thenReturn(20);
        new ArenaDialogs(server.plugin).edit(player, "mines");
        verify(arenas, never()).saveBlocks(any());

        actions.get("Save Current Blocks").accept(mock(DialogResponseView.class), player);
        saving.complete(null);

        verify(arenas).saveBlocks(map);
        verify(player).sendMessage(argThat((Component message) -> plain(message)
                .equals("Template saved. Arena copies are updating automatically.")));
    }

    private ArenaMap arenaMap() {
        var maps = mock(MapManager.class);
        when(server.plugin.getMapManager()).thenReturn(maps);
        var map = new ArenaMap("mines");
        map.setDisplayName("The Mines");
        map.setCopies(20);
        when(maps.getMap("mines")).thenReturn(map);
        return map;
    }

    @Test void invalidSubmissionKeepsFormOpenAndCloseStillWorksWithoutFormValues() {
        new ArenaDialogs(server.plugin).create(player);
        submissions.getFirst().accept(mock(DialogResponseView.class), player);
        verifyNoInteractions(arenas);
        verify(player).sendMessage(any(Component.class));
        verify(player, never()).closeDialog();

        // A static action has no DialogResponseView and does not merge the text inputs.
        assertEquals(1, staticCallbacks.size());
        verify(provider).staticAction(any(ClickEvent.class));
        when(player.hasPermission("contender.master")).thenReturn(false);
        staticCallbacks.getFirst().accept(player);
        verify(player).closeDialog();
    }

    @Test void rejectsReservedInputBeforeShowingAnUnusableDialog() {
        var input = mock(TextDialogInput.class);
        when(input.key()).thenReturn("id");
        assertThrows(IllegalArgumentException.class, () ->
                new Dialogs(server.plugin).show(player, "Test", List.of(), List.of(input), List.of()));
        dialogs.verifyNoInteractions();
        assertTrue(staticCallbacks.isEmpty());
    }

    @Test void closeCannotBeUsedByAnotherPlayer() {
        new ArenaDialogs(server.plugin).create(player);
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        staticCallbacks.getFirst().accept(other);
        verify(other, never()).closeDialog();
        verify(player, never()).closeDialog();
    }

    @Test void closeRunsOnServerThreadAndIgnoresPlayersWhoDisconnected() {
        new ArenaDialogs(server.plugin).create(player);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
        staticCallbacks.getFirst().accept(player);
        verify(player, never()).closeDialog();
        server.scheduled.getFirst().run();
        verify(player).closeDialog();

        staticCallbacks.getFirst().accept(player);
        when(player.isOnline()).thenReturn(false);
        server.scheduled.getLast().run();
        verify(player, times(1)).closeDialog();
    }

    @Test void tournamentWithACompleteMapIdentifiesTheMissingKitAndOpensItsEditor() {
        MapManager maps = mock(MapManager.class);
        KitManager kits = mock(KitManager.class);
        DuelManager duels = mock(DuelManager.class);
        when(server.plugin.getMapManager()).thenReturn(maps);
        when(server.plugin.getKitManager()).thenReturn(kits);
        when(server.plugin.getDuelManager()).thenReturn(duels);
        when(server.plugin.getTournamentManager()).thenReturn(mock(TournamentManager.class));
        ArenaMap map = mock(ArenaMap.class);
        when(map.isComplete()).thenReturn(true);
        when(maps.getMaps()).thenReturn(List.of(map));
        when(kits.getKits()).thenReturn(List.of());

        new TournamentDialogs(server.plugin).open(player);
        actions.get("New Tournament").accept(mock(DialogResponseView.class), player);
        actions.get("Round Robin").accept(mock(DialogResponseView.class), player);

        String body = messages.getLast();
        assertTrue(body.contains("Maps with both spawns: 1 set up"));
        assertTrue(body.contains("Kits: None saved"));
        assertTrue(body.contains("Create New Kit"));
        assertFalse(body.contains("Open Maps to save"));
        verify(player, never()).sendMessage(any(Component.class));
        DuelSetup setup = new DuelSetup(player.getUniqueId());
        when(duels.createSetup(player.getUniqueId())).thenReturn(setup);
        actions.get("Edit Kits").accept(mock(DialogResponseView.class), player);
        assertEquals("Kits", plain(shownTitle));
        assertNotNull(actions.get("Create New Kit"));

    }

    @Test void tournamentWizardKeepsEditsWhenGoingBackAndDoesNotCreateBeforeReview() {
        MapManager maps = mock(MapManager.class); KitManager kits = mock(KitManager.class);
        DuelManager duels = mock(DuelManager.class); TournamentManager tournaments = mock(TournamentManager.class);
        when(server.plugin.getMapManager()).thenReturn(maps); when(server.plugin.getKitManager()).thenReturn(kits);
        when(server.plugin.getDuelManager()).thenReturn(duels); when(server.plugin.getTournamentManager()).thenReturn(tournaments);
        ArenaMap map = mock(ArenaMap.class); when(map.isComplete()).thenReturn(true);
        when(map.getId()).thenReturn("mines"); when(map.getDisplayName()).thenReturn("The Mines");
        when(maps.getMaps()).thenReturn(List.of(map)); when(maps.getMap("mines")).thenReturn(map);
        Kit kit = new Kit("sword"); when(kits.getKits()).thenReturn(List.of(kit)); when(kits.getKit("sword")).thenReturn(kit);
        new TournamentDialogs(server.plugin).open(player);
        var response = mock(DialogResponseView.class);
        when(response.getText("name")).thenReturn("Sword stage"); when(response.getText("map")).thenReturn("mines");
        when(response.getText("kit")).thenReturn("sword"); when(response.getText("roster")).thenReturn("teams");
        when(response.getText("players")).thenReturn("Red: Alice, Bob\nBlue: Carol, Dana");
        when(response.getText("schedule")).thenReturn("rounds");
        when(response.getFloat("wins")).thenReturn(2f); when(response.getFloat("delay")).thenReturn(15f); when(response.getFloat("parallel")).thenReturn(4f);
        actions.get("New Tournament").accept(response, player);
        actions.get("Round Robin").accept(response, player);
        assertEquals(List.of("name", "map", "kit"), inputKeys);
        assertWizardScreen("Next: Players", "Details");
        actions.get("Next: Players").accept(response, player);
        assertWizardScreen("Next: Rules", "Players");
        actions.get("Next: Rules").accept(response, player);
        assertWizardScreen("Next: Review", "Rules");
        actions.get("Next: Review").accept(response, player);
        assertWizardScreen("Create Tournament", "Review");
        assertTrue(shownBody.stream().map(part -> plain(((PlainMessageDialogBody) part).contents()))
                .anyMatch(text -> text.contains("Title Preview") && text.contains("Sword stage")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("First to 2 round wins")));
        verify(tournaments, never()).create(any());
        actions.get("Back").accept(response, player);
        assertWizardScreen("Next: Review", "Rules");
        actions.get("Back").accept(response, player);
        assertWizardScreen("Next: Rules", "Players");
        assertEquals("Red: Alice, Bob\nBlue: Carol, Dana", initialText.get("players"));
        actions.get("Back").accept(response, player);
        assertWizardScreen("Next: Players", "Details");
        assertEquals("Sword stage", initialText.get("name"));
        verify(player, never()).sendMessage(any(Component.class));
    }

    @Test void tournamentWizardPreservesTheRoundLimitAndCreatesThatScheduleForOfflinePlayers() {
        readyDuel();
        new TournamentDialogs(server.plugin).open(player);
        var response = duelResponse();
        when(response.getText("name")).thenReturn("Axe Stage");
        when(response.getText("roster")).thenReturn("solo");
        when(response.getText("players")).thenReturn("Alice Bob Carol Dana Eve Frank Grace Heidi Ivan Judy");
        when(response.getText("schedule")).thenReturn("rounds");
        when(response.getFloat("parallel")).thenReturn(20f);
        when(response.getFloat("bracket_rounds")).thenReturn(3f);
        actions.get("New Tournament").accept(response, player);
        actions.get("Round Robin").accept(response, player);
        actions.get("Next: Players").accept(response, player);
        actions.get("Next: Rules").accept(response, player);
        assertWizardScreen("Next: Review", "Rules");
        assertEquals(9f, initialNumbers.get("bracket_rounds"));
        actions.get("Next: Review").accept(response, player);
        assertTrue(messages.stream().anyMatch(message -> message.contains("Bracket rounds: 3 of 9") && message.contains("Matches: 15")));
        actions.get("Back").accept(response, player);
        assertEquals(3f, initialNumbers.get("bracket_rounds"));
        actions.get("Next: Review").accept(response, player);
        try (var identities = mockStatic(me.advait.contender.player.PlayerIdentityResolver.class)) {
            identities.when(() -> me.advait.contender.player.PlayerIdentityResolver.resolve(anyString())).thenAnswer(call ->
                    CompletableFuture.completedFuture(new me.advait.contender.tournament.RosterParser.PlayerIdentity(UUID.randomUUID(), call.getArgument(0))));
            actions.get("Create Tournament").accept(response, player);
            server.scheduled.getLast().run();
        }
        var created = org.mockito.ArgumentCaptor.forClass(me.advait.contender.tournament.Tournament.class);
        verify(server.plugin.getTournamentManager()).create(created.capture());
        assertEquals(3, created.getValue().rounds());
        assertEquals(15, created.getValue().matches().size());
        assertEquals(10, created.getValue().entries().size());
        assertTrue(created.getValue().waitForRound());
        assertFalse(created.getValue().isRunning());
        verify(player, never()).sendMessage(argThat((Component message) -> plain(message).contains("Couldn't")));
    }

    @Test void tournamentMenuKeepsThePrimaryActionFirstAndToolsSeparateInEveryState() {
        var tournaments = mock(TournamentManager.class);
        when(server.plugin.getTournamentManager()).thenReturn(tournaments);
        when(server.plugin.getMapManager()).thenReturn(mock(MapManager.class));
        when(server.plugin.getKitManager()).thenReturn(mock(KitManager.class));
        when(tournaments.waitingReason()).thenReturn("");
        var menus = new TournamentDialogs(server.plugin);
        menus.open(player);
        assertMenu("New Tournament", "Setup Tools");
        var tournament = mock(me.advait.contender.tournament.Tournament.class);
        when(tournament.name()).thenReturn("Sword Stage");
        when(tournament.statusText()).thenReturn("Paused");
        when(tournament.mapId()).thenReturn("mines");
        when(tournament.kitId()).thenReturn("sword");
        when(tournaments.current()).thenReturn(tournament);
        menus.open(player);
        assertMenu("Start Tournament", "Matches", "Bracket & Standings", "Setup Tools", "Cancel Tournament");
        assertEquals(DialogPalette.DANGER, shownButtons.getLast().label().children().getLast().color());
        when(tournament.isRunning()).thenReturn(true);
        menus.open(player);
        assertMenu("Pause Tournament", "Matches", "Bracket & Standings", "Setup Tools", "Cancel Tournament");
        when(tournament.isCancelled()).thenReturn(true);
        menus.open(player);
        assertMenu("New Tournament", "Match Results", "Bracket & Standings", "Setup Tools");
        when(tournament.isCancelled()).thenReturn(false);
        when(tournament.isComplete()).thenReturn(true);
        menus.open(player);
        assertMenu("New Tournament", "Match Results", "Bracket & Standings", "Setup Tools");

        actions.get("Setup Tools").accept(mock(DialogResponseView.class), player);
        assertEquals(1, shownColumns);
        assertEquals(List.of("Minigames", "Maps & Kits", "Board & Lobby", "Tab Title", "Force Cancel All"), shownButtons.stream().map(b -> plain(b.label())).toList());
        assertEquals("Back", plain(shownFooter.label()));
        actions.get("Tab Title").accept(mock(DialogResponseView.class), player);
        assertEquals(List.of("Show Preview", "Save"), shownButtons.stream().map(b -> plain(b.label())).toList());
        assertEquals(List.of(150, 150), shownButtons.stream().map(ActionButton::width).toList());
        var edited = mock(DialogResponseView.class);
        when(edited.getText("title")).thenReturn("Sword Finals");
        when(edited.getText("color")).thenReturn("aqua");
        when(edited.getText("enabled")).thenReturn("true");
        actions.get("Show Preview").accept(edited, player);
        assertEquals("Sword Finals", initialText.get("title"));
        assertTrue(shownBody.stream().anyMatch(part -> plain(((PlainMessageDialogBody) part).contents()).contains("Sword Finals")));
        verify(server.plugin, never()).saveConfig();
        actions.get("Back").accept(mock(DialogResponseView.class), player);
        assertEquals("Setup Tools", plain(shownTitle));
        actions.get("Board & Lobby").accept(mock(DialogResponseView.class), player);
        actions.get("Board Appearance").accept(mock(DialogResponseView.class), player);
        assertEquals(List.of("Back", "Save"), shownButtons.stream().map(b -> plain(b.label())).toList());
        actions.get("Back").accept(mock(DialogResponseView.class), player);
        assertEquals("Board & Lobby", plain(shownTitle));
        actions.get("Back").accept(mock(DialogResponseView.class), player);
        actions.get("Back").accept(mock(DialogResponseView.class), player);
        assertMenu("New Tournament", "Match Results", "Bracket & Standings", "Setup Tools");
    }

    @Test void matchPaginationUsesASeparateRowWithConsistentWidths() {
        var tournaments = mock(TournamentManager.class);
        when(server.plugin.getTournamentManager()).thenReturn(tournaments);
        when(server.plugin.getMapManager()).thenReturn(mock(MapManager.class));
        when(server.plugin.getKitManager()).thenReturn(mock(KitManager.class));
        var entries = List.of("Alice", "Bob", "Carol", "Dana", "Eve").stream()
                .map(name -> new me.advait.contender.tournament.TournamentEntry(name, List.of(UUID.randomUUID()))).toList();
        var tournament = new me.advait.contender.tournament.Tournament(UUID.randomUUID(), "Sword", "mines", "sword", entries,
                false, false, 3, 10, 20);
        when(tournaments.current()).thenReturn(tournament);
        new TournamentDialogs(server.plugin).open(player);
        actions.get("Matches").accept(mock(DialogResponseView.class), player);
        assertEquals(2, shownColumns);
        assertEquals(10, shownButtons.size(), "Eight matches and one navigation row");
        assertEquals("---", plain(shownButtons.get(8).label()));
        assertEquals("Next Page", plain(shownButtons.get(9).label()));
        for (var button : shownButtons) assertEquals(220, button.width());
        actions.get("Next Page").accept(mock(DialogResponseView.class), player);
        assertEquals(4, shownButtons.size(), "Two matches and one navigation row");
        assertEquals("Previous Page", plain(shownButtons.get(2).label()));
        assertEquals("---", plain(shownButtons.get(3).label()));
        for (var button : shownButtons) assertEquals(220, button.width());
    }

    @Test void dialogBodiesAndActionsRemoveNestedBoldWithoutLosingColorOrSprites() {
        Component formatted = Component.text("Prefix").decorate(TextDecoration.BOLD)
                .append(DialogIcon.PREVIEW.label("Preview", DialogPalette.ACCENT).decorate(TextDecoration.BOLD));
        var screens = new Dialogs(server.plugin);
        screens.show(player, "Preview", List.of(DialogBody.plainMessage(formatted, 300)), List.of(),
                List.of(screens.button(player, formatted, false, (p, response) -> {})));
        assertRegular(shownTitle);
        assertRegular(((PlainMessageDialogBody) shownBody.getFirst()).contents());
        Component label = shownButtons.getFirst().label();
        assertRegular(label);
        assertInstanceOf(ObjectComponent.class, label.children().getFirst().children().getFirst());
        assertEquals(DialogPalette.ACCENT, label.children().getFirst().children().getLast().color());
    }

    private void assertMenu(String... labels) {
        assertEquals(1, shownColumns, "Hub actions must form a single centered column");
        assertEquals(List.of(labels), shownButtons.stream().map(button -> plain(button.label())).toList());
        assertEquals(DialogPalette.ACCENT, shownButtons.getFirst().label().children().getLast().color());
        for (var button : shownButtons) { assertEquals(300, button.width()); assertRegular(button.label()); }
        assertCloseFooter();
    }
    private void assertWizardScreen(String next, String currentStep) {
        assertEquals(2, shownColumns);
        assertEquals(List.of("Back", next), shownButtons.stream().map(button -> plain(button.label())).toList());
        assertEquals(List.of(150, 150), shownButtons.stream().map(ActionButton::width).toList());
        assertEquals(DialogPalette.MUTED, shownButtons.getFirst().label().children().getLast().color());
        assertEquals(DialogPalette.ACCENT, shownButtons.getLast().label().children().getLast().color());
        assertCloseFooter();
        assertRegular(shownTitle);
        for (var button : shownButtons) assertRegular(button.label());
        for (var part : shownBody) assertRegular(((PlainMessageDialogBody) part).contents());
        Component steps = ((PlainMessageDialogBody) shownBody.getFirst()).contents();
        assertTrue(steps.children().stream().anyMatch(part -> plain(part).equals(currentStep) && DialogPalette.ACCENT.equals(part.color())));
        for (var input : shownInputs) {
            int width = switch (input) {
                case TextDialogInput text -> text.width();
                case NumberRangeDialogInput range -> range.width();
                case io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput select -> select.width();
                default -> throw new AssertionError("Unknown form input");
            };
            assertEquals(300, width);
        }
    }
    private void assertCloseFooter() {
        assertEquals("Close", plain(shownFooter.label()));
        assertTrue(shownFooter.label().children().isEmpty(), "Close has no sprite");
        assertEquals(150, shownFooter.width());
    }
    private static String plain(Component component) { return textContents(component).strip(); }
    private static String textContents(Component component) {
        StringBuilder text = new StringBuilder(component instanceof TextComponent literal ? literal.content() : "");
        component.children().forEach(child -> text.append(textContents(child)));
        return text.toString();
    }
    private static void assertRegular(Component component) {
        assertEquals(TextDecoration.State.FALSE, component.decoration(TextDecoration.BOLD));
        component.children().forEach(DialogSubmissionTest::assertRegular);
    }

    @Test void spectatorMenuUsesOneMatchButtonWithNamesAndScore() {
        var duels = mock(DuelManager.class); when(server.plugin.getDuelManager()).thenReturn(duels);
        var tournaments = mock(TournamentManager.class); when(server.plugin.getTournamentManager()).thenReturn(tournaments);
        var tags = mock(me.advait.contender.nametag.NameTagManager.class); when(server.plugin.getNameTagManager()).thenReturn(tags);
        when(tags.displayName(any(UUID.class), anyString())).thenAnswer(call -> Component.text(call.getArgument(1, String.class)));
        var first = new me.advait.contender.duel.DuelTeam("Alice"); var second = new me.advait.contender.duel.DuelTeam("Bob");
        first.addPlayer(UUID.randomUUID()); second.addPlayer(UUID.randomUUID()); second.incrementScore();
        var profile = mock(com.destroystokyo.paper.profile.PlayerProfile.class); when(profile.getProperties()).thenReturn(java.util.Set.of());
        for (var team : List.of(first, second)) {
            var offline = mock(org.bukkit.OfflinePlayer.class); when(offline.getName()).thenReturn(team.getName()); when(offline.getPlayerProfile()).thenReturn(profile);
            bukkit.when(() -> Bukkit.getOfflinePlayer(team.getPlayers().getFirst())).thenReturn(offline);
        }
        var duel = mock(me.advait.contender.duel.Duel.class); var state = mock(me.advait.contender.duel.AbstractDuelState.class);
        when(duel.getState()).thenReturn(state); when(state.canAddSpectator()).thenReturn(true);
        when(duel.getTeam1()).thenReturn(first); when(duel.getTeam2()).thenReturn(second);
        when(duels.getActiveDuels()).thenReturn(List.of(duel)); when(tournaments.playing()).thenReturn(Map.of(7, duel));
        new DuelDialogs(server.plugin).spectate(player);
        assertTrue(buttonLabels.stream().noneMatch(label -> label.startsWith("Watch match")));
        assertEquals(3, submissions.size(), "One match button, lobby, and refresh");
        assertEquals(List.of("Alice vs  Bob  0-1", "---", "Return to Lobby", "Refresh"),
                shownButtons.stream().map(button -> plain(button.label())).toList());
        assertTrue(shownBody.isEmpty(), "A single page needs no instructions or page count");
        assertEquals(2, shownColumns);
        assertEquals(300, shownButtons.getFirst().width());
        click(shownButtons.getFirst());
        verify(duels).spectate(player, duel);
        verify(player).closeDialog();
        // An odd final page still starts tools and navigation on their own paired rows.
        when(duels.getActiveDuels()).thenReturn(java.util.Collections.nCopies(9, duel));
        new DuelDialogs(server.plugin).spectate(player);
        assertEquals(List.of("---", "Next Page"), shownButtons.subList(10, 12).stream().map(button -> plain(button.label())).toList());
        click(shownButtons.getLast());
        assertEquals(List.of("Alice vs  Bob  0-1", "---", "Return to Lobby", "Refresh", "Previous Page", "---"),
                shownButtons.stream().map(button -> plain(button.label())).toList());
        verify(player, never()).sendMessage(any(Component.class));
    }

    @Test void duelWithASavedKitOnlyAsksForTheMissingMapSpawns() {
        MapManager maps = mock(MapManager.class);
        KitManager kits = mock(KitManager.class);
        when(server.plugin.getMapManager()).thenReturn(maps);
        when(server.plugin.getKitManager()).thenReturn(kits);
        when(maps.getMaps()).thenReturn(List.of(new ArenaMap("unfinished")));
        when(kits.getKits()).thenReturn(List.of(new Kit("axe")));

        new DuelDialogs(server.plugin).open(player);

        String body = messages.getLast();
        assertTrue(body.contains("Maps with both spawns: None set up"));
        assertTrue(body.contains("Kits: 1 saved"));
        assertTrue(body.contains("Open Maps to save"));
        assertFalse(body.contains("Create New Kit"));
        assertNotNull(actions.get("Maps"));
        assertNotNull(actions.get("Check Again"));
    }

    private List<Player> readyDuel(String... names) {
        MapManager maps = mock(MapManager.class); KitManager kits = mock(KitManager.class);
        DuelManager duels = mock(DuelManager.class);
        when(server.plugin.getMapManager()).thenReturn(maps); when(server.plugin.getKitManager()).thenReturn(kits);
        when(server.plugin.getDuelManager()).thenReturn(duels);
        when(server.plugin.getTournamentManager()).thenReturn(mock(TournamentManager.class));
        ArenaMap map = mock(ArenaMap.class); when(map.isComplete()).thenReturn(true);
        when(map.getId()).thenReturn("mines"); when(map.getDisplayName()).thenReturn("The Mines");
        when(maps.getMaps()).thenReturn(List.of(map)); when(maps.getMap("mines")).thenReturn(map);
        Kit kit = new Kit("axe"); kit.setIcon(org.bukkit.Material.IRON_AXE);
        when(kits.getKits()).thenReturn(List.of(kit)); when(kits.getKit("axe")).thenReturn(kit);
        List<Player> contestants = new ArrayList<>();
        for (String name : names) {
            var contestant = mock(Player.class); UUID id = UUID.randomUUID();
            when(contestant.getUniqueId()).thenReturn(id); when(contestant.getName()).thenReturn(name);
            when(contestant.isOnline()).thenReturn(true);
            var profile = mock(com.destroystokyo.paper.profile.PlayerProfile.class);
            when(profile.getProperties()).thenReturn(java.util.Set.of()); when(contestant.getPlayerProfile()).thenReturn(profile);
            when(duels.isEligible(id)).thenReturn(true);
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(contestant);
            bukkit.when(() -> Bukkit.getOfflinePlayer(id)).thenReturn(contestant);
            contestants.add(contestant);
        }
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(contestants);
        return contestants;
    }
    private DialogResponseView duelResponse() {
        var response = mock(DialogResponseView.class);
        when(response.getText("map")).thenReturn("mines"); when(response.getText("kit")).thenReturn("axe");
        when(response.getText("mode")).thenReturn("teams");
        when(response.getFloat("wins")).thenReturn(2f); when(response.getFloat("delay")).thenReturn(15f);
        return response;
    }
    private void click(ActionButton button) {
        assertNotNull(button.action(), "The button must be enabled");
        callbackByAction.get(button.action()).accept(mock(DialogResponseView.class), player);
    }

    @Test void duelPickerKeepsTeamsInTwoColumnsPreventsDuplicatesAndCentersStartOnReview() {
        var contestants = readyDuel("Alice", "Bob");
        new DuelDialogs(server.plugin).open(player);
        assertEquals(List.of("Setup Tools", "Next: Players"), shownButtons.stream().map(b -> plain(b.label())).toList());
        for (var input : shownInputs) {
            Component label = input instanceof NumberRangeDialogInput range ? range.label()
                    : ((io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput) input).label();
            assertInstanceOf(ObjectComponent.class, label.children().getFirst(), "Every form field has a sprite");
        }
        actions.get("Next: Players").accept(duelResponse(), player);
        assertEquals(2, shownColumns);
        assertEquals(List.of("Team 1 (0)", "Team 2 (0)", "Alice", "Alice", "Bob", "Bob", "Back", "Next: Review"),
                shownButtons.stream().map(b -> plain(b.label())).toList());
        var oldAliceRight = callbackByAction.get(shownButtons.get(3).action());
        click(shownButtons.get(2));
        assertEquals("Team 1 (1)", plain(shownButtons.get(0).label()));
        assertNull(shownButtons.get(3).action(), "The other team cannot select Alice");
        oldAliceRight.accept(mock(DialogResponseView.class), player);
        verify(player).sendMessage(argThat((Component message) -> plain(message).contains("already on the other team")));
        click(shownButtons.get(2)); // Deselecting makes both sides available again.
        assertNotNull(shownButtons.get(3).action());
        click(shownButtons.get(2)); click(shownButtons.get(5));
        actions.get("Back").accept(mock(DialogResponseView.class), player);
        actions.get("Next: Players").accept(duelResponse(), player);
        assertEquals("Team 1 (1)", plain(shownButtons.get(0).label()));
        assertEquals("Team 2 (1)", plain(shownButtons.get(1).label()));
        actions.get("Next: Review").accept(mock(DialogResponseView.class), player);
        assertEquals(1, shownColumns);
        assertEquals(List.of("Start Duel"), shownButtons.stream().map(b -> plain(b.label())).toList());
        assertEquals(300, shownButtons.getFirst().width());
        assertEquals("Back", plain(shownFooter.label()));
        verify(server.plugin.getDuelManager(), never()).startDuel(any());
        var start = shownButtons.getFirst(); click(start); click(start);
        var chosen = org.mockito.ArgumentCaptor.forClass(DuelSetup.class);
        verify(server.plugin.getDuelManager(), times(1)).startDuel(chosen.capture());
        assertEquals(List.of(contestants.get(0).getUniqueId()), chosen.getValue().getTeam1().getPlayers());
        assertEquals(List.of(contestants.get(1).getUniqueId()), chosen.getValue().getTeam2().getPlayers());
        assertEquals(3, chosen.getValue().getRounds()); assertEquals(15, chosen.getValue().getPreRoundDelay());
    }

    @Test void duelPickerFiltersUnavailablePlayersAndRechecksAPlayerAtClickTime() {
        var contestants = readyDuel("Alice", "Bob", "Carol", "Dana");
        when(server.plugin.getDuelManager().isEligible(contestants.get(1).getUniqueId())).thenReturn(false);
        when(server.plugin.getTournamentManager().isReserved(contestants.get(2).getUniqueId())).thenReturn(true);
        when(server.plugin.getDuelManager().isPlaying(contestants.get(3).getUniqueId())).thenReturn(true);
        new DuelDialogs(server.plugin).open(player);
        actions.get("Next: Players").accept(duelResponse(), player);
        assertEquals(List.of("Team 1 (0)", "Team 2 (0)", "Alice", "Alice", "Back", "Next: Review"),
                shownButtons.stream().map(b -> plain(b.label())).toList());
        when(contestants.getFirst().isOnline()).thenReturn(false);
        click(shownButtons.get(2));
        verify(player).sendMessage(argThat((Component message) -> plain(message).contains("no longer available")));
        actions.get("Next: Review").accept(mock(DialogResponseView.class), player);
        assertEquals("Choose Players", plain(shownTitle));
        verify(server.plugin.getDuelManager(), never()).startDuel(any());
    }

    @Test void duelPlayerPagingStaysOnItsOwnBottomRowAndKeepsSelectionsAcrossPages() {
        readyDuel("Alice", "Bob", "Carol", "Dana", "Eve", "Frank", "Grace", "Hank", "Ivy");
        new DuelDialogs(server.plugin).open(player);
        actions.get("Next: Players").accept(duelResponse(), player);
        click(shownButtons.get(2));
        assertEquals(22, shownButtons.size());
        assertEquals(List.of("---", "Next Page"), shownButtons.subList(20, 22).stream().map(b -> plain(b.label())).toList());
        actions.get("Next Page").accept(mock(DialogResponseView.class), player);
        assertEquals(8, shownButtons.size());
        assertEquals(List.of("Previous Page", "---"), shownButtons.subList(6, 8).stream().map(b -> plain(b.label())).toList());
        for (var button : shownButtons) assertEquals(220, button.width());
        click(shownButtons.get(3));
        actions.get("Next: Review").accept(mock(DialogResponseView.class), player);
        assertTrue(shownBody.stream().anyMatch(part -> plain(((PlainMessageDialogBody) part).contents()).contains("Alice")));
        assertTrue(shownBody.stream().anyMatch(part -> plain(((PlainMessageDialogBody) part).contents()).contains("Ivy")));
    }

    private void readyOptions() {
        when(player.hasPermission("contender.admin")).thenReturn(true);
        when(server.plugin.getDataFolder()).thenReturn(settingsFolder.toFile());
        var roles = mock(me.advait.contender.role.RoleManager.class);
        when(roles.getRole(any())).thenReturn(me.advait.contender.role.PlayerRole.CONTESTANT);
        when(server.plugin.getRoleManager()).thenReturn(roles);
        var chat = new me.advait.contender.chat.ChatSettings(server.plugin);
        var pvp = new me.advait.contender.pvp.PvPSettings(server.plugin);
        when(server.plugin.getChatSettings()).thenReturn(chat);
        when(server.plugin.getPvpSettings()).thenReturn(pvp);
        var lobby = new me.advait.contender.lobby.LobbyManager(server.plugin);
        when(server.plugin.getLobbyManager()).thenReturn(lobby);
    }
    private void assertSettingsForm() {
        assertEquals(2, shownColumns);
        assertEquals(List.of("Back", "Save"), shownButtons.stream().map(button -> plain(button.label())).toList());
        assertEquals(List.of(150, 150), shownButtons.stream().map(ActionButton::width).toList());
        for (var input : shownInputs) {
            var option = (io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput) input;
            assertEquals(300, option.width());
            assertInstanceOf(ObjectComponent.class, option.label().children().getFirst());
        }
        for (var button : shownButtons) assertRegular(button.label());
        assertCloseFooter();
    }
    @Test void chatDialogsSaveEveryCategoryAndAdminBypassToTheExistingSettingsFile() {
        readyOptions();
        var screens = new GameSettingsDialogs(server.plugin);
        var response = mock(DialogResponseView.class);
        for (String group : List.of("Spectators", "Contestants", "Lobby")) {
            screens.chat(player);
            assertEquals(1, shownColumns);
            actions.get(group).accept(response, player);
            assertSettingsForm();
            when(response.getText("game_chat")).thenReturn(group.equals("Spectators") ? "true" : "false");
            when(response.getText("mute_voice")).thenReturn(group.equals("Spectators") ? "false" : "true");
            when(response.getText("deafen_voice")).thenReturn(group.equals("Spectators") ? "false" : "true");
            when(response.getText("hear_match")).thenReturn("true");
            actions.get("Save").accept(response, player);
            assertEquals("Chat & Voice", plain(shownTitle));
        }
        actions.get("Admin Bypass").accept(response, player);
        assertSettingsForm(); when(response.getText("override")).thenReturn("false");
        actions.get("Save").accept(response, player);
        var saved = new me.advait.contender.chat.ChatSettings(server.plugin);
        assertTrue(saved.isAllowSpectatorGameChat()); assertFalse(saved.isMuteSpectatorVoiceChat()); assertFalse(saved.isDeafenSpectatorVoiceChat());
        assertFalse(saved.isAllowContestantGameChat()); assertTrue(saved.isMuteContestantVoiceChat()); assertTrue(saved.isDeafenContestantVoiceChat());
        assertFalse(saved.isAllowLobbyGameChat()); assertTrue(saved.isMuteLobbyVoiceChat()); assertFalse(saved.isAdminsOverrideAll());
        verify(player, never()).openInventory(any(org.bukkit.inventory.Inventory.class));
    }
    @Test void pvpDialogBackDiscardsEditsAndSaveRechecksPermissionsAndValidatesBeforeMutating() {
        readyOptions(); when(player.hasPermission("contender.master")).thenReturn(false);
        new SettingsDialogs(server.plugin).open(player);
        assertEquals(List.of("Lobby Settings", "Chat & Voice", "PvP Settings"), shownButtons.stream().map(button -> plain(button.label())).toList());
        var response = mock(DialogResponseView.class);
        when(response.getText("lobby_pvp")).thenReturn("true"); when(response.getText("world_pvp")).thenReturn("false");
        when(response.getText("override")).thenReturn("false");
        actions.get("PvP Settings").accept(response, player); assertSettingsForm();
        actions.get("Back").accept(response, player);
        assertFalse(server.plugin.getPvpSettings().isAllowLobbyPvp());
        actions.get("PvP Settings").accept(response, player);
        when(player.hasPermission("contender.admin")).thenReturn(false);
        actions.get("Save").accept(response, player);
        assertFalse(server.plugin.getPvpSettings().isAllowLobbyPvp());
        when(player.hasPermission("contender.admin")).thenReturn(true);
        when(response.getText("override")).thenReturn("invalid");
        actions.get("Save").accept(response, player);
        assertFalse(server.plugin.getPvpSettings().isAllowLobbyPvp());
        when(response.getText("override")).thenReturn("false"); actions.get("Save").accept(response, player);
        var saved = new me.advait.contender.pvp.PvPSettings(server.plugin);
        assertTrue(saved.isAllowLobbyPvp()); assertFalse(saved.isAllowNonDuelWorldPvp()); assertFalse(saved.isAdminPvpOverride());
        assertEquals("Options", plain(shownTitle));
    }
    @Test void lobbyDialogValidatesBeforeSavingAndBackDiscardsChanges() {
        readyOptions(); when(player.hasPermission("contender.master")).thenReturn(false);
        var response = mock(DialogResponseView.class);
        when(response.getText("join_lobby")).thenReturn("false"); when(response.getText("break_blocks")).thenReturn("true");
        when(response.getText("place_blocks")).thenReturn("false"); when(response.getText("build_bypass")).thenReturn("false");
        new SettingsDialogs(server.plugin).open(player);
        actions.get("Lobby Settings").accept(response, player); assertSettingsForm();
        assertEquals(List.of("join_lobby", "break_blocks", "place_blocks", "build_bypass"), inputKeys);
        actions.get("Back").accept(response, player);
        assertTrue(server.plugin.getLobbyManager().isTeleportOnJoin()); verify(server.plugin, never()).saveConfig();
        actions.get("Lobby Settings").accept(response, player);
        when(player.hasPermission("contender.admin")).thenReturn(false);
        actions.get("Save").accept(response, player); verify(server.plugin, never()).saveConfig();
        when(player.hasPermission("contender.admin")).thenReturn(true);
        when(response.getText("build_bypass")).thenReturn("invalid");
        actions.get("Save").accept(response, player);
        assertTrue(server.plugin.getLobbyManager().isTeleportOnJoin()); verify(server.plugin, never()).saveConfig();
        when(response.getText("build_bypass")).thenReturn("false"); actions.get("Save").accept(response, player);
        var lobby = server.plugin.getLobbyManager();
        assertFalse(lobby.isTeleportOnJoin()); assertTrue(lobby.isAllowBlockBreak()); assertFalse(lobby.isAllowBlockPlace());
        assertFalse(lobby.isAdminBuildBypass()); verify(server.plugin).saveConfig(); assertEquals("Options", plain(shownTitle));
    }
    @Test void voteDialogsPageCandidatesValidateRolesAndRejectStaleSessionButtons() {
        var candidates = readyDuel("Alice", "Bob", "Carol", "Dana", "Eve", "Frank", "Grace", "Heidi", "Ivan");
        readyOptions();
        var roles = server.plugin.getRoleManager();
        when(roles.isContestant(any())).thenReturn(true);
        doReturn(candidates).when(server.server).getOnlinePlayers();
        when(server.server.getPlayer(any(UUID.class))).thenAnswer(call -> candidates.stream()
                .filter(candidate -> candidate.getUniqueId().equals(call.getArgument(0))).findFirst().orElse(null));
        var manager = mock(me.advait.contender.vote.VoteManager.class);
        when(server.plugin.getVoteManager()).thenReturn(manager);
        var session = new me.advait.contender.vote.VoteSession(server.plugin, manager, 60);
        when(manager.getActiveSession()).thenReturn(session);
        new VoteDialogs(server.plugin).open(player);
        assertEquals(2, shownColumns);
        assertEquals(List.of("---", "Next Page"), shownButtons.subList(10, 12).stream().map(button -> plain(button.label())).toList());
        click(shownButtons.getFirst());
        assertEquals(candidates.getFirst().getUniqueId(), session.getVoteFor(player.getUniqueId()));
        assertEquals(1, session.getVoteCount(candidates.getFirst().getUniqueId()));
        click(shownButtons.getFirst());
        assertNull(session.getVoteFor(player.getUniqueId()));
        when(roles.isContestant(player.getUniqueId())).thenReturn(false);
        click(shownButtons.getFirst());
        assertNull(session.getVoteFor(player.getUniqueId()));
        when(roles.isContestant(player.getUniqueId())).thenReturn(true);
        actions.get("Next Page").accept(mock(DialogResponseView.class), player);
        assertEquals(List.of("Previous Page", "---"), shownButtons.subList(4, 6).stream().map(button -> plain(button.label())).toList());
        click(shownButtons.getFirst());
        assertEquals(candidates.getLast().getUniqueId(), session.getVoteFor(player.getUniqueId()));
        actions.get("Manage Candidates").accept(mock(DialogResponseView.class), player);
        var remove = shownButtons.getFirst();
        when(player.hasPermission("contender.master")).thenReturn(false);
        click(remove); assertFalse(session.isRemoved(candidates.getFirst().getUniqueId()));
        when(player.hasPermission("contender.master")).thenReturn(true);
        click(remove); assertTrue(session.isRemoved(candidates.getFirst().getUniqueId()));
        var stale = shownButtons.getFirst();
        when(manager.getActiveSession()).thenReturn(new me.advait.contender.vote.VoteSession(server.plugin, manager, 60));
        click(stale);
        assertFalse(session.isRemoved(candidates.get(1).getUniqueId()));
        verify(player).closeDialog();
        verify(player, never()).openInventory(any(org.bukkit.inventory.Inventory.class));
    }
    @Test void leavingTheKitDialogReturnsWithTheSameTeams() {
        var contestants = readyDuel("Alice", "Bob");
        var setup = new DuelSetup(player.getUniqueId()); setup.getTeam1().addPlayer(contestants.getFirst().getUniqueId());
        new KitDialogs(server.plugin).open(player, p -> new DuelDialogs(server.plugin).open(p, setup));
        assertEquals("Kits", plain(shownTitle));
        actions.get("Back").accept(mock(DialogResponseView.class), player);
        assertEquals("New Duel", plain(shownTitle));
        actions.get("Next: Players").accept(duelResponse(), player);
        assertEquals("Team 1 (1)", plain(shownButtons.getFirst().label()));
    }
}
