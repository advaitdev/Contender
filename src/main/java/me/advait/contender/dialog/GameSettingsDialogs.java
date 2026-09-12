package me.advait.contender.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import me.advait.contender.Contender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static me.advait.contender.dialog.DialogPalette.*;

/** Server-wide lobby, chat, voice, and PvP controls. Changes are committed only by Save. */
public final class GameSettingsDialogs {
    private static final String VOICE_NOTICE = "Contender's voice controls are disabled. Simple Voice Chat handles audio normally.";
    private final Contender plugin;
    private final Dialogs dialogs;
    public GameSettingsDialogs(Contender plugin) { this.plugin = plugin; dialogs = new Dialogs(plugin); }
    private void requireAdmin(Player player) {
        if (!player.hasPermission("contender.admin")) throw new IllegalStateException("You don't have permission to change these settings.");
    }
    private ActionButton button(Player player, DialogIcon icon, String label, boolean primary, int width,
                                BiConsumer<Player, DialogResponseView> callback) {
        return dialogs.button(player, icon.label(label, primary ? ACCENT : icon == DialogIcon.BACK ? MUTED : TEXT), null, false, width,
                (p, view) -> { requireAdmin(p); callback.accept(p, view); });
    }
    private DialogInput toggle(String key, DialogIcon icon, String label, boolean value, String yes, String no) {
        return DialogInput.singleOption(key, icon.label(label), List.of(Dialogs.option("true", yes, value),
                Dialogs.option("false", no, !value))).width(300).build();
    }
    private boolean value(DialogResponseView view, String key) {
        return switch (Dialogs.text(view, key)) {
            case "true" -> true; case "false" -> false;
            default -> throw new IllegalArgumentException("Choose a value for each setting.");
        };
    }
    public void lobby(Player player) {
        requireAdmin(player);
        var lobby = plugin.getLobbyManager();
        form(player, "Lobby Settings", "Use /setlobby to save your position and facing.\n\n"
                + "Admin bypass lets players with contender.admin build here.\nSpectator mode still prevents building.", List.of(
                toggle("join_lobby", DialogIcon.SPAWN, "Join at Lobby", lobby.isTeleportOnJoin(), "Enabled", "Disabled"),
                toggle("break_blocks", DialogIcon.AXE, "Break Blocks", lobby.isAllowBlockBreak(), "Allowed", "Blocked"),
                toggle("place_blocks", DialogIcon.MAP, "Place Blocks", lobby.isAllowBlockPlace(), "Allowed", "Blocked"),
                toggle("build_bypass", DialogIcon.SETTINGS, "Admin Build Bypass", lobby.isAdminBuildBypass(), "Enabled", "Disabled")),
                p -> new SettingsDialogs(plugin).open(p), (p, view) -> {
                    boolean join = value(view, "join_lobby"), breaking = value(view, "break_blocks"),
                            placing = value(view, "place_blocks"), bypass = value(view, "build_bypass");
                    var config = plugin.getConfig();
                    config.set("lobby.teleport-on-join", join); config.set("lobby.allow-block-break", breaking);
                    config.set("lobby.allow-block-place", placing); config.set("lobby.admin-build-bypass", bypass);
                    plugin.saveConfig(); new SettingsDialogs(plugin).open(p);
                });
    }
    public void chat(Player player) {
        requireAdmin(player);
        List<ActionButton> buttons = new ArrayList<>();
        for (String group : List.of("Lobby", "Contestants", "Spectators")) {
            buttons.add(button(player, group.equals("Lobby") ? DialogIcon.SPAWN : DialogIcon.PLAYERS, group, false, 300,
                    (p, view) -> chatGroup(p, group)));
        }
        buttons.add(button(player, DialogIcon.SETTINGS, "Admin Bypass", false, 300, (p, view) -> chatBypass(p)));
        dialogs.show(player, "Chat & Voice", List.of(DialogBody.plainMessage(DialogText.muted(
                "Choose whose game chat permissions to edit.\n\n" + VOICE_NOTICE), 320)), List.of(), buttons, 1, 150,
                button(player, DialogIcon.BACK, "Back", false, 150, (p, view) -> new SettingsDialogs(plugin).open(p)));
    }
    private void chatGroup(Player player, String group) {
        var settings = plugin.getChatSettings();
        boolean lobby = group.equals("Lobby"), spectator = group.equals("Spectators");
        boolean allowChat = lobby ? settings.isAllowLobbyGameChat() : spectator ? settings.isAllowSpectatorGameChat() : settings.isAllowContestantGameChat();
        boolean mute = lobby ? settings.isMuteLobbyVoiceChat() : spectator ? settings.isMuteSpectatorVoiceChat() : settings.isMuteContestantVoiceChat();
        boolean deafen = spectator ? settings.isDeafenSpectatorVoiceChat() : settings.isDeafenContestantVoiceChat();
        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(toggle("game_chat", DialogIcon.CHAT, "Game Chat", allowChat, "Allowed", "Blocked"));
        inputs.add(toggle("mute_voice", DialogIcon.VOICE, "Speaking in Voice Chat", mute, "Muted", "Allowed"));
        if (!lobby) inputs.add(toggle("deafen_voice", DialogIcon.VOICE, "Hearing Voice Chat", deafen, "Deafened", "Allowed"));
        if (spectator) inputs.add(toggle("hear_match", DialogIcon.DUEL, "Hear the Match", settings.isSpectatorsHearMatch(), "Allowed", "Blocked"));
        String description = lobby ? "Applies to contestants outside a match."
                : spectator ? "Applies to directors and spectators."
                : "Applies to contestants playing a match.";
        form(player, group + " Chat", description + "\n\n" + VOICE_NOTICE + "\nSaved voice options have no effect.", inputs, this::chat, (p, view) -> {
            boolean game = value(view, "game_chat"), muted = value(view, "mute_voice"), deafened = !lobby && value(view, "deafen_voice");
            boolean hearMatch = spectator && value(view, "hear_match");
            if (lobby) { settings.setAllowLobbyGameChat(game); settings.setMuteLobbyVoiceChat(muted); }
            else if (spectator) { settings.setAllowSpectatorGameChat(game); settings.setMuteSpectatorVoiceChat(muted); settings.setDeafenSpectatorVoiceChat(deafened); settings.setSpectatorsHearMatch(hearMatch); }
            else { settings.setAllowContestantGameChat(game); settings.setMuteContestantVoiceChat(muted); settings.setDeafenContestantVoiceChat(deafened); }
            settings.save(); chat(p);
        });
    }
    private void chatBypass(Player player) {
        var settings = plugin.getChatSettings();
        form(player, "Chat Admin Bypass", "Players with contender.admin can bypass game chat restrictions.\n\n" + VOICE_NOTICE,
                List.of(toggle("override", DialogIcon.SETTINGS, "Admin Bypass", settings.isAdminsOverrideAll(), "Enabled", "Disabled")), this::chat, (p, view) -> {
                    settings.setAdminsOverrideAll(value(view, "override")); settings.save(); chat(p);
                });
    }
    public void pvp(Player player) {
        requireAdmin(player);
        var settings = plugin.getPvpSettings();
        form(player, "PvP Settings", "Control combat outside matches.\nDuel rules and arena protection still apply.", List.of(
                toggle("lobby_pvp", DialogIcon.SPAWN, "Players Outside Matches", settings.isAllowLobbyPvp(), "PvP Allowed", "PvP Blocked"),
                toggle("world_pvp", DialogIcon.MAP, "Worlds Without Matches", settings.isAllowNonDuelWorldPvp(), "PvP Allowed", "PvP Blocked"),
                toggle("override", DialogIcon.SETTINGS, "Admin Bypass", settings.isAdminPvpOverride(), "Enabled", "Disabled")),
                p -> new SettingsDialogs(plugin).open(p), (p, view) -> {
                    boolean lobby = value(view, "lobby_pvp"), world = value(view, "world_pvp"), admin = value(view, "override");
                    settings.setAllowLobbyPvp(lobby); settings.setAllowNonDuelWorldPvp(world); settings.setAdminPvpOverride(admin);
                    settings.save(); new SettingsDialogs(plugin).open(p);
                });
    }
    private void form(Player player, String title, String hint, List<DialogInput> inputs, Consumer<Player> back,
                      BiConsumer<Player, DialogResponseView> save) {
        requireAdmin(player);
        dialogs.show(player, title, List.of(DialogBody.plainMessage(DialogText.muted(hint), 320)), inputs,
                List.of(button(player, DialogIcon.BACK, "Back", false, 150, (p, view) -> back.accept(p)),
                        button(player, DialogIcon.SAVE, "Save", true, 150, save)), 2, 150, null);
    }
}
