package me.advait.contender.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryBuilderFactory;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogInstancesProvider;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.MultiActionType;
import io.papermc.paper.registry.data.dialog.type.NoticeType;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.kit.Kit;
import me.advait.contender.kit.KitManager;
import me.advait.contender.manhunt.ManhuntManager;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.MapManager;
import me.advait.contender.player.PlayerIdentityResolver;
import me.advait.contender.race.RaceCourse;
import me.advait.contender.race.RaceManager;
import me.advait.contender.role.RoleManager;
import me.advait.contender.testutil.StateTestServer;
import me.advait.contender.tournament.RosterParser.PlayerIdentity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Keeps Paper's dialog constraints while running the real wizard callbacks. */
class MinigameDialogLookupTest {
    private final StateTestServer server = new StateTestServer();
    private final Player owner = mock(Player.class);
    private final DialogInstancesProvider provider = mock(DialogInstancesProvider.class);
    private final Map<DialogAction, DialogActionCallback> callbacks = new IdentityHashMap<>();
    private final CompletableFuture<PlayerIdentity> alice = new CompletableFuture<>();
    private final CompletableFuture<PlayerIdentity> bob = new CompletableFuture<>();
    private final DialogResponseView response = mock(DialogResponseView.class);
    private String title;
    private List<ActionButton> buttons;
    private ActionButton exit;
    private boolean notice;
    private MockedStatic<DialogInstancesProvider> providers;
    private MockedStatic<RegistryAccess> registries;
    private MockedStatic<Dialog> dialogs;
    private MockedStatic<ClickEvent> clicks;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<PlayerIdentityResolver> identities;

    @BeforeEach void setUp() {
        when(owner.getUniqueId()).thenReturn(UUID.randomUUID());
        when(owner.isOnline()).thenReturn(true);
        when(owner.hasPermission("contender.master")).thenReturn(true);
        when(server.plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(server.plugin.getDuelManager()).thenReturn(mock(DuelManager.class));
        when(server.plugin.getRoleManager()).thenReturn(mock(RoleManager.class));

        providers = mockStatic(DialogInstancesProvider.class);
        providers.when(DialogInstancesProvider::instance).thenReturn(provider);
        registries = mockStatic(RegistryAccess.class);
        var registryAccess = mock(RegistryAccess.class, RETURNS_MOCKS);
        registries.when(RegistryAccess::registryAccess).thenReturn(registryAccess);
        doReturn(mock(Registry.class)).when(registryAccess).getRegistry(RegistryKey.DIALOG);
        dialogs = mockStatic(Dialog.class);
        dialogs.when(() -> Dialog.create(any())).thenAnswer(call -> {
            RegistryBuilderFactory<Dialog, DialogRegistryEntry.Builder> factory = mock(RegistryBuilderFactory.class);
            when(factory.empty()).thenReturn(mock(DialogRegistryEntry.Builder.class, RETURNS_SELF));
            Consumer<RegistryBuilderFactory<Dialog, DialogRegistryEntry.Builder>> build = call.getArgument(0);
            build.accept(factory);
            return mock(Dialog.class);
        });
        when(provider.dialogBaseBuilder(any())).thenAnswer(call -> {
            title = plain(call.getArgument(0));
            var builder = mock(DialogBase.Builder.class, RETURNS_SELF);
            when(builder.build()).thenReturn(mock(DialogBase.class));
            return builder;
        });
        when(provider.multiAction(anyList())).thenAnswer(call -> {
            buttons = List.copyOf(call.getArgument(0));
            // Paper's MultiActionTypeImpl rejects this even when an exit action exists.
            if (buttons.isEmpty()) throw new IllegalArgumentException("actions cannot be empty");
            notice = false;
            var builder = mock(MultiActionType.Builder.class, RETURNS_SELF);
            when(builder.exitAction(any())).thenAnswer(action -> { exit = action.getArgument(0); return builder; });
            when(builder.build()).thenReturn(mock(MultiActionType.class));
            return builder;
        });
        when(provider.notice(any(ActionButton.class))).thenAnswer(call -> {
            notice = true;
            exit = call.getArgument(0);
            buttons = List.of(exit);
            return mock(NoticeType.class);
        });
        when(provider.actionButtonBuilder(any())).thenAnswer(call -> {
            var button = mock(ActionButton.class);
            when(button.label()).thenReturn(call.getArgument(0));
            var builder = mock(ActionButton.Builder.class, RETURNS_SELF);
            when(builder.action(any())).thenAnswer(action -> { when(button.action()).thenReturn(action.getArgument(0)); return builder; });
            when(builder.width(anyInt())).thenAnswer(width -> { when(button.width()).thenReturn(width.getArgument(0)); return builder; });
            when(builder.build()).thenReturn(button);
            return builder;
        });
        when(provider.register(any(), any())).thenAnswer(call -> {
            var action = mock(DialogAction.CustomClickAction.class);
            callbacks.put(action, call.getArgument(0));
            return action;
        });
        when(provider.plainMessageDialogBody(any(), anyInt())).thenAnswer(call -> {
            var body = mock(PlainMessageDialogBody.class);
            when(body.contents()).thenReturn(call.getArgument(0));
            when(body.width()).thenReturn(call.getArgument(1));
            return body;
        });
        when(provider.textBuilder(anyString(), any())).thenAnswer(call -> {
            var input = mock(TextDialogInput.class);
            when(input.key()).thenReturn(call.getArgument(0));
            var builder = mock(TextDialogInput.Builder.class, RETURNS_SELF);
            when(builder.build()).thenReturn(input);
            return builder;
        });
        when(provider.singleOptionEntry(anyString(), any(), anyBoolean())).thenReturn(mock(SingleOptionDialogInput.OptionEntry.class));
        when(provider.singleOptionBuilder(anyString(), any(), anyList())).thenAnswer(call -> {
            var input = mock(SingleOptionDialogInput.class);
            when(input.key()).thenReturn(call.getArgument(0));
            var builder = mock(SingleOptionDialogInput.Builder.class, RETURNS_SELF);
            when(builder.build()).thenReturn(input);
            return builder;
        });
        clicks = mockStatic(ClickEvent.class);
        clicks.when(() -> ClickEvent.callback(any(), any(ClickCallback.Options.class))).thenReturn(mock(ClickEvent.class));
        when(provider.staticAction(any())).thenReturn(mock(DialogAction.StaticAction.class));
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        bukkit.when(Bukkit::getScheduler).thenReturn(server.scheduler);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
        identities = mockStatic(PlayerIdentityResolver.class);
        identities.when(() -> PlayerIdentityResolver.resolve("Alice")).thenReturn(alice);
        identities.when(() -> PlayerIdentityResolver.resolve("Bob")).thenReturn(bob);

        var map = mock(ArenaMap.class);
        when(map.getId()).thenReturn("mines");
        when(map.getDisplayName()).thenReturn("The Mines");
        when(map.isComplete()).thenReturn(true);
        var maps = mock(MapManager.class);
        when(maps.getMaps()).thenReturn(List.of(map));
        when(maps.getMap("mines")).thenReturn(map);
        when(server.plugin.getMapManager()).thenReturn(maps);
        var kit = new Kit("sword");
        var kits = mock(KitManager.class);
        when(kits.getKits()).thenReturn(List.of(kit));
        when(kits.getKit("sword")).thenReturn(kit);
        when(server.plugin.getKitManager()).thenReturn(kits);
        var races = mock(RaceManager.class);
        var course = new RaceCourse("mines", 15, "Finish", 3, true);
        when(races.courses()).thenReturn(List.of(course));
        when(races.course("mines")).thenReturn(course);
        when(server.plugin.getRaceManager()).thenReturn(races);
        var manhunt = mock(ManhuntManager.class);
        when(manhunt.worldReady()).thenReturn(true);
        when(manhunt.worldName()).thenReturn("contender_manhunt");
        when(server.plugin.getManhuntManager()).thenReturn(manhunt);
        when(response.getText(anyString())).thenAnswer(call -> switch ((String) call.getArgument(0)) {
            case "name" -> "Test Event";
            case "map", "course" -> "mines";
            case "kit" -> "sword";
            case "players" -> "Alice, Bob";
            case "difficulty" -> "NORMAL";
            case "grace" -> "5";
            case "advance" -> "15";
            case "minutes" -> "0";
            case "teams" -> "random";
            case "seconds" -> "10";
            default -> null;
        });
    }

    @AfterEach void tearDown() {
        if (identities != null) identities.close();
        if (bukkit != null) bukkit.close();
        if (clicks != null) clicks.close();
        if (dialogs != null) dialogs.close();
        if (registries != null) registries.close();
        if (providers != null) providers.close();
        server.close();
    }

    @ParameterizedTest @ValueSource(strings = {"Combo", "Mace Race", "Manhunt"})
    void nextReviewShowsANoticeWhileResolvingThenOpensTheReview(String game) {
        openLookup(game);

        assertTrue(notice);
        assertEquals(game.equals("Mace Race") ? "Finding Racers" : "Finding Players", title);
        assertEquals("Back", plain(exit.label()));
        assertNotNull(exit.action());
        assertEquals(150, exit.width());
        assertTrue(server.scheduled.isEmpty());

        finishLookup();

        assertFalse(notice);
        assertEquals("Review " + game, title);
        assertEquals(List.of("Back", game.equals("Mace Race") ? "Create Race" : "Create " + game), labels());
        verify(owner, never()).sendMessage(any(Component.class));
    }

    @ParameterizedTest @ValueSource(strings = {"Combo", "Mace Race", "Manhunt"})
    void backFromLookupPreservesTheRosterScreenWhenTheOldLookupFinishes(String game) {
        openLookup(game);
        click(exit);
        assertEquals(game + " · Players", title);

        finishLookup();

        assertEquals(game + " · Players", title);
        assertEquals(List.of("Back", "Next: Rules"), labels());
        verify(owner, never()).sendMessage(any(Component.class));
    }

    @Test void aScreenWithoutActionsOrCustomFooterStillHasACloseButton() {
        new Dialogs(server.plugin).show(owner, "Leaderboard", List.of(DialogBody.plainMessage(Component.text("No scores yet."), 300)),
                List.of(), List.of(), 1, 150, null);

        assertTrue(notice);
        assertEquals(List.of("Close"), labels());
        assertNotNull(exit.action());
    }

    private void openLookup(String game) {
        switch (game) {
            case "Combo" -> new ComboDialogs(server.plugin).create(owner);
            case "Mace Race" -> new RaceDialogs(server.plugin).create(owner);
            case "Manhunt" -> new ManhuntDialogs(server.plugin).create(owner);
            default -> throw new IllegalArgumentException(game);
        }
        click("Next: Players");
        click("Next: Rules");
        click("Next: Review");
    }

    private void finishLookup() {
        alice.complete(new PlayerIdentity(UUID.randomUUID(), "Alice"));
        bob.complete(new PlayerIdentity(UUID.randomUUID(), "Bob"));
        assertEquals(1, server.scheduled.size());
        server.scheduled.getFirst().run();
    }

    private List<String> labels() { return buttons.stream().map(button -> plain(button.label())).toList(); }
    private void click(String label) {
        click(buttons.stream().filter(button -> plain(button.label()).equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing " + label + " on " + title + ": " + labels())));
    }
    private void click(ActionButton button) { callbacks.get(button.action()).accept(response, owner); }
    private static String plain(Component component) { return textContents(component).strip(); }
    private static String textContents(Component component) {
        StringBuilder text = new StringBuilder(component instanceof TextComponent literal ? literal.content() : "");
        component.children().forEach(child -> text.append(textContents(child)));
        return text.toString();
    }
}
