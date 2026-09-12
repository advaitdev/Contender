package me.advait.contender.chat;

import me.advait.contender.Contender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

public class ChatSettings {

    private final Contender plugin;
    private final File file;

    private boolean allowSpectatorGameChat = false;
    private boolean muteSpectatorVoiceChat = false;
    private boolean deafenSpectatorVoiceChat = false;
    private boolean spectatorsHearMatch = true;
    private boolean allowContestantGameChat = true;
    private boolean muteContestantVoiceChat = false;
    private boolean deafenContestantVoiceChat = false;
    private boolean adminsOverrideAll = true;
    private boolean allowLobbyGameChat = true;
    private boolean muteLobbyVoiceChat = false;

    public ChatSettings(Contender plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "chat_settings.yml");
        load();
    }

    public void load() {
        if (!file.exists()) {
            save();
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        allowSpectatorGameChat = config.getBoolean("allow-spectator-game-chat", false);
        muteSpectatorVoiceChat = config.getBoolean("mute-spectator-voice-chat", false);
        deafenSpectatorVoiceChat = config.getBoolean("deafen-spectator-voice-chat", false);
        spectatorsHearMatch = config.getBoolean("spectators-hear-match", true);
        allowContestantGameChat = config.getBoolean("allow-contestant-game-chat", true);
        muteContestantVoiceChat = config.getBoolean("mute-contestant-voice-chat", false);
        deafenContestantVoiceChat = config.getBoolean("deafen-contestant-voice-chat", false);
        adminsOverrideAll = config.getBoolean("admins-override-all", true);
        allowLobbyGameChat = config.getBoolean("allow-lobby-game-chat", true);
        muteLobbyVoiceChat = config.getBoolean("mute-lobby-voice-chat", false);
        if (config.getInt("spectator-routing-version", 0) < 1) {
            // Replace the old blanket isolation defaults with the private spectator channel.
            if (muteSpectatorVoiceChat && deafenSpectatorVoiceChat) {
                muteSpectatorVoiceChat = false;
                deafenSpectatorVoiceChat = false;
            }
            save();
        }
    }

    public void save() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("spectator-routing-version", 1);
        config.set("spectators-hear-match", spectatorsHearMatch);
        config.set("allow-spectator-game-chat", allowSpectatorGameChat);
        config.set("mute-spectator-voice-chat", muteSpectatorVoiceChat);
        config.set("deafen-spectator-voice-chat", deafenSpectatorVoiceChat);
        config.set("allow-contestant-game-chat", allowContestantGameChat);
        config.set("mute-contestant-voice-chat", muteContestantVoiceChat);
        config.set("deafen-contestant-voice-chat", deafenContestantVoiceChat);
        config.set("admins-override-all", adminsOverrideAll);
        config.set("allow-lobby-game-chat", allowLobbyGameChat);
        config.set("mute-lobby-voice-chat", muteLobbyVoiceChat);
        try {
            config.save(file);
            plugin.refreshVoiceRouting();
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save chat_settings.yml: " + e.getMessage());
        }
    }

    // Getters
    public boolean isAllowSpectatorGameChat() { return allowSpectatorGameChat; }
    public boolean isMuteSpectatorVoiceChat() { return muteSpectatorVoiceChat; }
    public boolean isSpectatorsHearMatch() { return spectatorsHearMatch; }
    public void setSpectatorsHearMatch(boolean value) { spectatorsHearMatch = value; }
    public boolean isDeafenSpectatorVoiceChat() { return deafenSpectatorVoiceChat; }
    public boolean isAllowContestantGameChat() { return allowContestantGameChat; }
    public boolean isMuteContestantVoiceChat() { return muteContestantVoiceChat; }
    public boolean isDeafenContestantVoiceChat() { return deafenContestantVoiceChat; }
    public boolean isAdminsOverrideAll() { return adminsOverrideAll; }
    public boolean isAllowLobbyGameChat() { return allowLobbyGameChat; }
    public boolean isMuteLobbyVoiceChat() { return muteLobbyVoiceChat; }

    // Setters
    public void setAllowSpectatorGameChat(boolean v) { this.allowSpectatorGameChat = v; }
    public void setMuteSpectatorVoiceChat(boolean v) { this.muteSpectatorVoiceChat = v; }
    public void setDeafenSpectatorVoiceChat(boolean v) { this.deafenSpectatorVoiceChat = v; }
    public void setAllowContestantGameChat(boolean v) { this.allowContestantGameChat = v; }
    public void setMuteContestantVoiceChat(boolean v) { this.muteContestantVoiceChat = v; }
    public void setDeafenContestantVoiceChat(boolean v) { this.deafenContestantVoiceChat = v; }
    public void setAdminsOverrideAll(boolean v) { this.adminsOverrideAll = v; }
    public void setAllowLobbyGameChat(boolean v) { this.allowLobbyGameChat = v; }
    public void setMuteLobbyVoiceChat(boolean v) { this.muteLobbyVoiceChat = v; }
}
