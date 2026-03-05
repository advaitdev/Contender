package me.advait.contender.duel;

import me.advait.contender.Contender;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

public class DuelManager {

    private final Contender plugin;
    private final Map<UUID, DuelSetup> activeSetups;
    private final Map<UUID, Duel> playerDuelMap;
    private final List<Duel> activeDuels;

    public DuelManager(Contender plugin) {
        this.plugin = plugin;
        this.activeSetups = new HashMap<>();
        this.playerDuelMap = new HashMap<>();
        this.activeDuels = new ArrayList<>();
    }

    public DuelSetup createSetup(UUID creator) {
        DuelSetup setup = new DuelSetup(creator);
        activeSetups.put(creator, setup);
        return setup;
    }

    public DuelSetup getSetup(UUID creator) {
        return activeSetups.get(creator);
    }

    public void removeSetup(UUID creator) {
        activeSetups.remove(creator);
    }

    public Duel startDuel(DuelSetup setup) {
        Duel duel = new Duel(plugin, this, setup);
        activeDuels.add(duel);

        for (UUID uuid : duel.getAllDuelPlayers()) {
            playerDuelMap.put(uuid, duel);
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID uuid = online.getUniqueId();
            if (!isInDuel(uuid) && !duel.getAllDuelPlayers().contains(uuid)) {
                duel.addSpectator(uuid);
                playerDuelMap.put(uuid, duel);
            }
        }

        activeSetups.remove(setup.getCreator());

        duel.start();
        return duel;
    }

    public void endDuel(Duel duel) {
        activeDuels.remove(duel);
        for (UUID uuid : duel.getAllParticipants()) {
            playerDuelMap.remove(uuid);
        }
    }

    public Duel getDuel(UUID playerUuid) {
        return playerDuelMap.get(playerUuid);
    }

    public Duel getDuel(Player player) {
        return getDuel(player.getUniqueId());
    }

    public boolean isInDuel(UUID uuid) {
        return playerDuelMap.containsKey(uuid);
    }

    public boolean isInDuel(Player player) {
        return isInDuel(player.getUniqueId());
    }

    public List<Duel> getActiveDuels() {
        return Collections.unmodifiableList(activeDuels);
    }

    public void cleanup() {
        for (Duel duel : new ArrayList<>(activeDuels)) {
            duel.forceEnd();
        }
        activeSetups.clear();
    }
}
