package me.advait.contender;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.keys.DialogKeys;
import io.papermc.paper.registry.tag.TagKey;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import me.advait.contender.dialog.DialogIcon;
import me.advait.contender.dialog.DialogPalette;
import net.kyori.adventure.key.Key;
import java.util.Set;

/** The launcher is static client data; access to the live settings is checked on the server. */
public final class ContenderBootstrap implements PluginBootstrap {
    public static final Key OPEN_HACKS = Key.key("contender:open_hacks");
    @Override public void bootstrap(BootstrapContext context) {
        var key = DialogKeys.create(Key.key("contender:quick_menu"));
        context.getLifecycleManager().registerEventHandler(RegistryEvents.DIALOG.compose(), event -> event.registry().register(key, builder -> builder
                .base(DialogBase.builder(DialogPalette.text("Contender", DialogPalette.ACCENT))
                        .externalTitle(DialogPalette.text("Contender", DialogPalette.TEXT)).pause(false).canCloseWithEscape(true).build())
                .type(DialogType.multiAction(java.util.List.of(ActionButton.builder(DialogIcon.SETTINGS.label("Hacks", DialogPalette.ACCENT))
                        .width(300).action(DialogAction.customClick(OPEN_HACKS, null)).build()),
                        ActionButton.builder(DialogPalette.text("Close", DialogPalette.MUTED)).width(300).build(), 1))));
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.TAGS.postFlatten(RegistryKey.DIALOG), event ->
                event.registrar().addToTag(TagKey.create(RegistryKey.DIALOG, Key.key("minecraft:quick_actions")), Set.of(key)));
    }
}
