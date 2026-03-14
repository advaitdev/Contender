package me.advait.contender.duel;

import me.advait.contender.Contender;
import me.advait.contender.spectator.SpectatorManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

public class DuelManager {

    private final Contender plugin;
    private final SpectatorManager spectatorManager;
    private final Map<UUID, DuelSetup> activeSetups;
    private final Map<UUID, Duel> playerDuelMap;
    private final List<Duel> activeDuels;

    public DuelManager(Contender plugin, SpectatorManager spectatorManager) {
        this.plugin = plugin;
        this.spectatorManager = spectatorManager;
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
        if (setup.getMode() == DuelMode.FFA) {
            setup.getTeam1().clearPlayers();
            setup.getTeam2().clearPlayers();
            List<Player> candidates = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                UUID uuid = online.getUniqueId();
                if (!spectatorManager.isDeceased(uuid) && !isInDuel(uuid)) {
                    candidates.add(online);
                }
            }
            Collections.shuffle(candidates);
            for (int i = 0; i < candidates.size(); i++) {
                if (i % 2 == 0) setup.getTeam1().addPlayer(candidates.get(i).getUniqueId());
                else setup.getTeam2().addPlayer(candidates.get(i).getUniqueId());
            }
        }

        Duel duel = new Duel(plugin, this, setup);
        activeDuels.add(duel);

        for (UUID uuid : duel.getAllDuelPlayers()) {
            playerDuelMap.put(uuid, duel);
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID uuid = online.getUniqueId();
            if (!isInDuel(uuid) && !duel.getAllDuelPlayers().contains(uuid)) {
                if (!spectatorManager.isDeceased(uuid)) {
                    online.getInventory().clear();
                }
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

    public boolean isMapInUse(String mapId) {
        for (Duel duel : activeDuels) {
            if (duel.getState() != DuelState.ENDED && duel.getMap().getId().equals(mapId)) {
                return true;
            }
        }
        return false;
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
