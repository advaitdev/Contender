package me.advait.contender;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.keys.DialogKeys;
import io.papermc.paper.registry.tag.TagKey;
import me.advait.contender.dialog.DialogIcon;
import me.advait.contender.dialog.DialogPalette;
import net.kyori.adventure.key.Key;

import java.util.List;
import java.util.Set;

/**
 * Adds a Contender menu to Minecraft's Quick Actions screen. It is static client data; what each
 * button may do is checked on the server when it is clicked.
 */
public final class ContenderBootstrap implements PluginBootstrap {
    public static final Key OPEN_HACKS = Key.key("contender:open_hacks");
    public static final Key OPEN_SPECTATE = Key.key("contender:open_spectate");
    public static final Key OPEN_VOTE = Key.key("contender:open_vote");
    public static final Key OPEN_LOBBY = Key.key("contender:open_lobby");

    @Override public void bootstrap(BootstrapContext context) {
        var key = DialogKeys.create(Key.key("contender:quick_menu"));
        context.getLifecycleManager().registerEventHandler(RegistryEvents.DIALOG.compose(), event -> event.registry().register(key, builder -> builder
                .base(DialogBase.builder(DialogPalette.text("Contender", DialogPalette.ACCENT))
                        .externalTitle(DialogPalette.text("Contender", DialogPalette.TEXT)).pause(false).canCloseWithEscape(true).build())
                .type(DialogType.multiAction(List.of(
                        button(DialogIcon.PREVIEW, "Spectate", OPEN_SPECTATE),
                        button(DialogIcon.STAR, "Vote", OPEN_VOTE),
                        button(DialogIcon.SPAWN, "Lobby", OPEN_LOBBY),
                        button(DialogIcon.SETTINGS, "Hacks", OPEN_HACKS)),
                        ActionButton.builder(DialogPalette.text("Close", DialogPalette.MUTED)).width(300).build(), 1))));
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.TAGS.postFlatten(RegistryKey.DIALOG), event ->
                event.registrar().addToTag(TagKey.create(RegistryKey.DIALOG, Key.key("minecraft:quick_actions")), Set.of(key)));
    }

    private static ActionButton button(DialogIcon icon, String label, Key action) {
        return ActionButton.builder(icon.label(label, DialogPalette.TEXT)).width(300).action(DialogAction.customClick(action, null)).build();
    }
}
