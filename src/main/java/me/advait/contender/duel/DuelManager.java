package me.advait.contender.duel;

import me.advait.contender.Contender;
import me.advait.contender.arena.ArenaLease;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class DuelManager {
    private final Contender plugin;
    private final Map<UUID, DuelSetup> activeSetups = new HashMap<>();
    private final Map<UUID, Duel> playerDuelMap = new ConcurrentHashMap<>();
    private final List<Duel> activeDuels = new ArrayList<>();
    private final Map<Duel, Consumer<DuelResult>> completions = new HashMap<>();
    private boolean stopping;

    public DuelManager(Contender plugin) {
        this.plugin = plugin;
    }
    public DuelSetup createSetup(UUID creator) {
        DuelSetup setup = new DuelSetup(creator);
        activeSetups.put(creator, setup);
        return setup;
    }
    public DuelSetup getSetup(UUID creator) { return activeSetups.get(creator); }
    public void removeSetup(UUID creator) { activeSetups.remove(creator); }
    public Duel startDuel(DuelSetup setup) { return startDuel(setup, null); }

    public Duel startDuel(DuelSetup setup, Consumer<DuelResult> completion) {
        if (stopping) throw new IllegalStateException("Duels are stopping.");
        if (plugin.getVoteManager().isVoteActive()) throw new IllegalStateException("Wait for the vote to finish.");
        if (setup.getMode() == DuelMode.FFA) {
            setup.getTeam1().clearPlayers();
            setup.getTeam2().clearPlayers();
            List<Player> candidates = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                UUID uuid = online.getUniqueId();
                if (isEligible(uuid) && !isInDuel(uuid) && !reservedForTournament(uuid)) candidates.add(online);
            }
            Collections.shuffle(candidates);
            for (int i = 0; i < candidates.size(); i++) {
                if (i % 2 == 0) setup.getTeam1().addPlayer(candidates.get(i).getUniqueId());
                else setup.getTeam2().addPlayer(candidates.get(i).getUniqueId());
            }
        }
        if (!setup.isValid() || setup.getTeam1().isEmpty() || setup.getTeam2().isEmpty()) {
            throw new IllegalArgumentException("Choose a kit, map, and players for both sides.");
        }
        for (UUID uuid : setup.getAllPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) throw new IllegalStateException("All selected players must be online.");
            requireEligible(uuid);
            if (minigameReserved(uuid)) throw new IllegalStateException(player.getName() + " is entered in a minigame or waiting to return to the lobby.");
            if (setup.getTeam1().hasPlayer(uuid) && setup.getTeam2().hasPlayer(uuid)) {
                throw new IllegalArgumentException("A player cannot be on both teams.");
            }
            Duel current = getDuel(uuid);
            if (current != null && current.isInDuel(uuid)) throw new IllegalStateException(player.getName() + " is already playing.");
            if (completion == null && reservedForTournament(uuid)) throw new IllegalStateException(player.getName() + " is entered in the tournament.");
        }
        ArenaLease arena = plugin.getArenaManager().acquire(setup.getSelectedMap().getId());
        if (arena == null) throw new IllegalStateException("No copies of this map are ready. Prepare them with /arena or wait for a free copy.");
        Duel duel;
        try {
            duel = new Duel(plugin, this, setup, arena, completion != null);
        } catch (RuntimeException | Error failure) {
            plugin.getArenaManager().release(arena);
            throw failure;
        }
        activeDuels.add(duel);
        if (completion != null) completions.put(duel, completion);
        try {
            for (UUID uuid : duel.getAllDuelPlayers()) {
                Player player = Bukkit.getPlayer(uuid);
                if (getDuel(uuid) != null) leaveSpectating(player);
                playerDuelMap.put(uuid, duel);
            }
            activeSetups.remove(setup.getCreator());
            duel.start();
            return duel;
        } catch (RuntimeException | Error failure) {
            duel.shutdown();
            throw failure;
        }
    }

    private boolean minigameReserved(UUID uuid) {
        if (plugin.getMinigameManager() != null) return plugin.getMinigameManager().isReserved(uuid) || plugin.getMinigameManager().pendingReturn(uuid);
        return plugin.getRaceManager() != null && (plugin.getRaceManager().isReserved(uuid) || plugin.getRaceManager().pendingReturn(uuid));
    }
    private boolean minigameOwns(UUID uuid) {
        if (plugin.getMinigameManager() != null) return plugin.getMinigameManager().owns(uuid) || plugin.getMinigameManager().pendingReturn(uuid);
        return plugin.getRaceManager() != null && (plugin.getRaceManager().owns(uuid) || plugin.getRaceManager().pendingReturn(uuid));
    }
    private boolean reservedForTournament(UUID uuid) {
        return plugin.getTournamentManager() != null && plugin.getTournamentManager().isReserved(uuid)
                || minigameReserved(uuid);
    }
    public boolean isEligible(UUID uuid) {
        return plugin.getRoleManager().isContestant(uuid);
    }
    public void requireEligible(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        String name = player == null ? uuid.toString() : player.getName();
        if (!plugin.getRoleManager().isContestant(uuid)) throw new IllegalArgumentException(name + " must be a contestant to play.");
    }
    public void endDuel(Duel duel) {
        if (!activeDuels.remove(duel)) return;
        for (UUID uuid : duel.getAllParticipants()) playerDuelMap.remove(uuid, duel);
        plugin.refreshVoiceRouting();
        plugin.refreshHackerAttributes();
        if (duel.getArena() != null) plugin.getArenaManager().release(duel.getArena());
        Consumer<DuelResult> completion = completions.remove(duel);
        if (completion != null && !stopping) completion.accept(duel.getResult());
    }
    public void spectate(Player player, Duel duel) {
        UUID uuid = player.getUniqueId();
        if (minigameOwns(uuid)) throw new IllegalStateException("Leave the minigame before spectating a duel.");
        if (!activeDuels.contains(duel) || !duel.getState().canAddSpectator()) throw new IllegalStateException("That match has ended.");
        Duel current = getDuel(uuid);
        if (current == duel) return;
        if (current != null && current.isInDuel(uuid)) throw new IllegalStateException("Finish your match before spectating.");
        if (current != null) leaveSpectating(player);
        player.getInventory().clear();
        playerDuelMap.put(uuid, duel);
        try { duel.addSpectator(uuid); }
        catch (RuntimeException failure) {
            playerDuelMap.remove(uuid, duel);
            duel.removeSpectator(player);
            throw failure;
        }
    }
    public void disconnectSpectator(Player player) {
        Duel duel = getDuel(player);
        if (duel == null) return;
        duel.disconnectSpectator(player);
        if (!duel.isInDuel(player.getUniqueId())) playerDuelMap.remove(player.getUniqueId(), duel);
    }
    public void leaveSpectating(Player player) {
        Duel duel = getDuel(player);
        if (duel == null) return;
        if (duel.isInDuel(player.getUniqueId())) throw new IllegalStateException("You are still in a match.");
        playerDuelMap.remove(player.getUniqueId(), duel);
        plugin.refreshVoiceRouting();
        plugin.refreshHackerAttributes();
        duel.removeSpectator(player);
    }
    public Duel getDuel(UUID playerUuid) { return playerDuelMap.get(playerUuid); }
    public Duel getDuel(Player player) { return getDuel(player.getUniqueId()); }
    public boolean isInDuel(UUID uuid) { return playerDuelMap.containsKey(uuid); }
    public boolean isInDuel(Player player) { return isInDuel(player.getUniqueId()); }
    public boolean isPlaying(UUID uuid) {
        Duel duel = getDuel(uuid);
        return duel != null && duel.isInDuel(uuid);
    }
    public boolean isMapInUse(String mapId) { return plugin.getArenaManager().available(mapId) == 0; }
    public List<Duel> getActiveDuels() { return Collections.unmodifiableList(activeDuels); }
    public void shutdown() {
        stopping = true;
        for (Duel duel : new ArrayList<>(activeDuels)) duel.shutdown();
        activeSetups.clear();
        completions.clear();
    }
    public void cleanup() {
        for (Duel duel : new ArrayList<>(activeDuels)) duel.forceEnd();
        activeSetups.clear();
    }
    public void forceCancelAll() {
        boolean wasStopping = stopping;
        stopping = true;
        completions.clear();
        activeSetups.clear();
        RuntimeException incomplete = new IllegalStateException("Some duels could not be fully cleaned up.");
        try {
            for (Duel duel : new ArrayList<>(activeDuels)) {
                try { duel.forceCancel(); }
                catch (RuntimeException failure) { incomplete.addSuppressed(failure); }
                finally {
                    activeDuels.remove(duel);
                    playerDuelMap.values().removeIf(value -> value == duel);
                }
            }
        } finally {
            activeDuels.clear();
            playerDuelMap.clear();
            stopping = wasStopping;
            plugin.refreshVoiceRouting();
            plugin.refreshHackerAttributes();
        }
        if (incomplete.getSuppressed().length > 0) throw incomplete;
    }
}
