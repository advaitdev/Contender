package me.advait.contender.core;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;

/** Vanilla sounds used across the plugin, so every screen sounds the same. */
public enum Sounds {
    CLICK("ui.button.click", 0.6f, 1f),
    SUCCESS("entity.player.levelup", 0.8f, 1.4f),
    ERROR("block.note_block.bass", 0.8f, 0.6f),
    TICK("block.note_block.hat", 1f, 1f),
    TICK_HIGH("block.note_block.pling", 0.8f, 1.6f),
    GO("block.note_block.pling", 1f, 2f),
    ROUND_WIN("entity.player.levelup", 0.8f, 1.5f),
    ROUND_LOSS("block.note_block.bass", 0.8f, 0.8f),
    ELIMINATED("entity.lightning_bolt.thunder", 0.4f, 1.3f),
    VICTORY("ui.toast.challenge_complete", 0.9f, 1f),
    ANNOUNCE("block.bell.use", 1f, 1.2f),
    REVEAL("block.beacon.activate", 1f, 1.4f),
    DRUM("block.note_block.basedrum", 1f, 1f),
    SABOTAGE("entity.elder_guardian.curse", 0.6f, 1.2f),
    WHOOSH("entity.breeze.wind_burst", 0.8f, 1f),
    FIZZLE("block.beacon.deactivate", 1f, 0.9f),
    POP("entity.item.pickup", 0.8f, 1.2f);

    private final Sound sound;

    Sounds(String key, float volume, float pitch) {
        sound = Sound.sound(Key.key(key), Sound.Source.MASTER, volume, pitch);
    }

    public void play(Player player) { player.playSound(sound, Sound.Emitter.self()); }

    public void play(Player player, float pitch) {
        player.playSound(Sound.sound(sound.name(), sound.source(), sound.volume(), pitch), Sound.Emitter.self());
    }

    public void play(Collection<UUID> players) {
        for (UUID id : players) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) play(player);
        }
    }

    public void playAll() { Bukkit.getOnlinePlayers().forEach(this::play); }
}
