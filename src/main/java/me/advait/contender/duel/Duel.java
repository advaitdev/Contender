package me.advait.contender.duel;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import me.advait.contender.Contender;
import me.advait.contender.kit.Kit;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.util.MessageUtil;
import me.advait.contender.util.StringUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class Duel {

    private final Contender plugin;
    private final DuelManager manager;
    private final Kit kit;
    private final ArenaMap map;
    private final DuelTeam team1;
    private final DuelTeam team2;
    private final int totalRounds;
    private final int preRoundDelay;
    private final Set<UUID> spectators;
    private final Set<Location> placedBlocks;
    private final Map<UUID, InventorySnapshot> sortedInventories;

    private DuelState state;
    private int currentRound;
    private BukkitTask activeTask;
    private boolean firstRound;
    private Boolean originalDoDaylightCycle;
    private Boolean originalDoMobSpawning;

    private volatile BlockArrayClipboard clipboard;
    private volatile boolean clipboardReady = false;

    private static ItemStack[] cloneArray(ItemStack[] arr) {
        if (arr == null) return new ItemStack[0];
        ItemStack[] clone = new ItemStack[arr.length];
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] != null) clone[i] = arr[i].clone();
        }
        return clone;
    }

    private record InventorySnapshot(ItemStack[] storage, ItemStack[] armor, ItemStack offhand) {
        InventorySnapshot {
            storage = Duel.cloneArray(storage);
            armor = Duel.cloneArray(armor);
            offhand = offhand != null ? offhand.clone() : null;
        }
    }

    public Duel(Contender plugin, DuelManager manager, DuelSetup setup) {
        this.plugin = plugin;
        this.manager = manager;
        this.kit = setup.getSelectedKit();
        this.map = setup.getSelectedMap();

        this.team1 = new DuelTeam(setup.getTeam1().getName());
        for (UUID uuid : setup.getTeam1().getPlayers()) {
            this.team1.addPlayer(uuid);
        }

        this.team2 = new DuelTeam(setup.getTeam2().getName());
        for (UUID uuid : setup.getTeam2().getPlayers()) {
            this.team2.addPlayer(uuid);
        }

        this.totalRounds = setup.getRounds();
        this.preRoundDelay = setup.getPreRoundDelay();
        this.spectators = new HashSet<>();
        this.placedBlocks = new HashSet<>();
        this.sortedInventories = new HashMap<>();
        this.state = DuelState.STARTING;
        this.currentRound = 0;
        this.firstRound = true;
    }

    public void start() {
        state = DuelState.STARTING;

        configureWorldRules();
        copyRegionAsync();

//        for (Player player : Bukkit.getOnlinePlayers()) {
//            if (player.isDead()) player.spigot().respawn();
//        }

        if (map.getTeam1Spawn() == null || map.getTeam2Spawn() == null) {
            for (UUID uuid : team1.getPlayers()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    player.sendMessage(Component.text("Couldn't start the duel because the spawn location was null").color(NamedTextColor.RED));
                }
            }
            for (UUID uuid : team2.getPlayers()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    player.sendMessage(Component.text("Couldn't start the duel because the spawn location was null").color(NamedTextColor.RED));
                }
            }
            this.endDuel();
            return;
        }

        for (UUID uuid : team1.getPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.setGameMode(GameMode.SURVIVAL);
                player.teleport(map.getTeam1Spawn());
                kit.apply(player);
            }
        }

        for (UUID uuid : team2.getPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.setGameMode(GameMode.SURVIVAL);
                player.teleport(map.getTeam2Spawn());
                kit.apply(player);
            }
        }

        Location specSpawn = map.getSpectatorSpawn();
        if (specSpawn != null) {
            for (UUID uuid : spectators) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    player.teleport(specSpawn);
                }
            }
        }

        MiniMessage mm = MiniMessage.miniMessage();
        String team1Names = buildTeamNames(team1);
        String team2Names = buildTeamNames(team2);
        Component vsMessage = mm.deserialize(
                "<color:gray>Starting duel: " +
                MessageUtil.FONT_OPEN +
                        "<color:" + MessageUtil.ERROR + ">" + team1Names + "</color>" +
                        " <color:gray>vs</color> " +
                        "<color:" + MessageUtil.SECONDARY + ">" + team2Names + "</color>" +
                        MessageUtil.FONT_CLOSE
        );
        for (UUID uuid : getAllParticipants()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.sendMessage(vsMessage);
            }
        }

        startSortingPhase();
    }

    private String buildTeamNames(DuelTeam team) {
        StringBuilder sb = new StringBuilder();
        List<UUID> players = team.getPlayers();
        for (int i = 0; i < players.size(); i++) {
            Player p = Bukkit.getPlayer(players.get(i));
            if (p != null) {
                if (i > 0) sb.append(", ");
                sb.append(p.getName());
            }
        }
        return sb.toString();
    }

    private void startSortingPhase() {
        state = DuelState.SORTING;

        broadcastActionBar("<color:" + MessageUtil.PRIMARY + ">Sort your inventory! " +
                "<color:" + MessageUtil.SECONDARY + ">" + preRoundDelay + "s</color></color>");

        final int[] countdown = {preRoundDelay};
        activeTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            countdown[0]--;

            if (countdown[0] <= 0) {
                activeTask.cancel();
                captureInventories();
                startRound();
                return;
            }

            if (countdown[0] <= 5) {
                broadcastActionBar("<color:" + MessageUtil.WARNING + ">Starting in " +
                        "<color:" + MessageUtil.PRIMARY + ">" + countdown[0] + "</color>...</color>");
                broadcastSound(SoundType.COUNTDOWN_TICK);
            } else {
                broadcastActionBar("<color:" + MessageUtil.PRIMARY + ">Sort your inventory! " +
                        "<color:" + MessageUtil.SECONDARY + ">" + countdown[0] + "s</color></color>");
                broadcastSound(SoundType.COUNTDOWN_TICK);
            }
        }, 20L, 20L);
    }

    private void captureInventories() {
        for (UUID uuid : getAllDuelPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                sortedInventories.put(uuid, new InventorySnapshot(
                        player.getInventory().getStorageContents(),
                        player.getInventory().getArmorContents(),
                        player.getInventory().getItemInOffHand()
                ));
            }
        }
    }

    private void restoreInventories() {
        for (UUID uuid : getAllDuelPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;

            InventorySnapshot snapshot = sortedInventories.get(uuid);
            if (snapshot != null) {
                player.getInventory().clear();
                player.getInventory().setStorageContents(cloneArray(snapshot.storage()));
                player.getInventory().setArmorContents(cloneArray(snapshot.armor()));
                if (snapshot.offhand() != null) {
                    player.getInventory().setItemInOffHand(snapshot.offhand().clone());
                }
            } else {
                kit.apply(player);
            }

            player.setHealth(20.0);
            player.setFoodLevel(20);
            player.setSaturation(5.0f);
            player.setFireTicks(0);
            player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        }
    }

    private void startRound() {
        if (state == DuelState.ENDED) return;
        currentRound++;
        state = DuelState.ACTIVE;

        team1.resetAlive();
        team2.resetAlive();

        for (UUID uuid : team1.getPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.setGameMode(GameMode.SURVIVAL);
                player.teleport(map.getTeam1Spawn());
            }
        }
        for (UUID uuid : team2.getPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.setGameMode(GameMode.SURVIVAL);
                player.teleport(map.getTeam2Spawn());
            }
        }

        if (!firstRound) {
            restoreInventories();
        }
        firstRound = false;

        broadcastActionBar("<color:" + MessageUtil.PRIMARY + ">Round " + currentRound + "/" + totalRounds +
                " <color:" + MessageUtil.SECONDARY + ">(" + team1.getScore() + " - " + team2.getScore() + ")</color></color>");
        broadcastSound(SoundType.COUNTDOWN_GO);
    }

    public void handleDeath(Player deadPlayer) {
        if (state != DuelState.ACTIVE) return;

        UUID deadUuid = deadPlayer.getUniqueId();
        DuelTeam deadTeam = getTeam(deadUuid);
        if (deadTeam == null) return;

        deadTeam.markDead(deadUuid);

        Player killer = deadPlayer.getKiller();
        Component killMsg;
        if (killer != null) {
            Component killerHead = StringUtil.getPlayerHead(killer);
            Component deadHead = StringUtil.getPlayerHead(deadPlayer);
            killMsg = Component.empty()
                    .append(deadHead).appendSpace()
                    .append(MiniMessage.miniMessage().deserialize(
                            MessageUtil.FONT_OPEN + "<color:" + MessageUtil.MUTED + ">" +
                                    deadPlayer.getName() + " was slain by </color>" + MessageUtil.FONT_CLOSE))
                    .append(killerHead).appendSpace()
                    .append(MiniMessage.miniMessage().deserialize(
                            MessageUtil.FONT_OPEN + "<color:" + MessageUtil.PRIMARY + ">" +
                                    killer.getName() + "</color>" + MessageUtil.FONT_CLOSE));
        } else {
            Component deadHead = StringUtil.getPlayerHead(deadPlayer);
            killMsg = Component.empty()
                    .append(deadHead).appendSpace()
                    .append(MiniMessage.miniMessage().deserialize(
                            MessageUtil.FONT_OPEN + "<color:" + MessageUtil.MUTED + ">" +
                                    deadPlayer.getName() + " has been eliminated!</color>" + MessageUtil.FONT_CLOSE));
        }

        for (UUID uuid : getAllParticipants()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.sendMessage(killMsg);
                MessageUtil.playElimination(p);
            }
        }

        if (deadTeam.isEliminated()) {
            DuelTeam winner = getOpponent(deadTeam);
            endRound(winner);
        }
    }

    private void endRound(DuelTeam winner) {
        state = DuelState.ROUND_END;
        winner.incrementScore();

        int roundsToWin = (totalRounds / 2) + 1;
        if (winner.getScore() >= roundsToWin || currentRound >= totalRounds) {
            Bukkit.getScheduler().runTaskLater(plugin, this::endDuel, 1L);
            return;
        }

        broadcastActionBar("<color:" + MessageUtil.PRIMARY + ">" + winner.getName() + " wins the round! " +
                "<color:" + MessageUtil.SECONDARY + ">" + team1.getScore() + " - " + team2.getScore() + "</color></color>");

        for (UUID uuid : winner.getPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                MessageUtil.playRoundWin(p);
            }
        }

        pasteRegionAsync(() -> {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                broadcastActionBar("<color:" + MessageUtil.WARNING + ">Next round in 3...</color>");
                broadcastSound(SoundType.COUNTDOWN_TICK);
            }, 20L);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                broadcastActionBar("<color:" + MessageUtil.WARNING + ">Next round in 2...</color>");
                broadcastSound(SoundType.COUNTDOWN_TICK);
            }, 40L);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                broadcastActionBar("<color:" + MessageUtil.WARNING + ">Next round in 1...</color>");
                broadcastSound(SoundType.COUNTDOWN_TICK);
            }, 60L);

            Bukkit.getScheduler().runTaskLater(plugin, this::startRound, 80L);
        });
    }

    public void endDuel() {
        if (state == DuelState.ENDED) return;
        state = DuelState.ENDED;

        if (activeTask != null) {
            activeTask.cancel();
        }

        MiniMessage mm = MiniMessage.miniMessage();
        boolean tie = team1.getScore() == team2.getScore();

        if (tie) {
            String msg = MessageUtil.FONT_OPEN + "<color:" + MessageUtil.WARNING + ">The duel ended in a tie! " +
                    "<color:" + MessageUtil.SECONDARY + ">" + team1.getScore() + " - " + team2.getScore() + "</color></color>" + MessageUtil.FONT_CLOSE;
            broadcastActionBar("<color:" + MessageUtil.WARNING + ">Tie! " +
                    "<color:" + MessageUtil.SECONDARY + ">" + team1.getScore() + " - " + team2.getScore() + "</color></color>");
            broadcastMessage(mm.deserialize(msg));
        } else {
            DuelTeam winner = team1.getScore() > team2.getScore() ? team1 : team2;
            String msg = MessageUtil.FONT_OPEN + "<color:" + MessageUtil.PRIMARY + ">" + winner.getName() + " wins the duel! " +
                    "<color:" + MessageUtil.SECONDARY + ">" + team1.getScore() + " - " + team2.getScore() + "</color></color>" + MessageUtil.FONT_CLOSE;
            broadcastActionBar("<color:" + MessageUtil.PRIMARY + ">" + winner.getName() + " wins the duel! " +
                    "<color:" + MessageUtil.SECONDARY + ">" + team1.getScore() + " - " + team2.getScore() + "</color></color>");
            broadcastMessage(mm.deserialize(msg));

            for (UUID uuid : winner.getPlayers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    MessageUtil.playDuelEnd(p);
                }
            }
        }

        restoreWorldRules();
        manager.endDuel(this);

        for (UUID uuid : getAllParticipants()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) plugin.getLobbyManager().sendToLobby(p);
        }

        cleanupEntitiesInRegion();

        // Async arena rollback — fire and forget after duel cleanup
        pasteRegionAsync(null);
    }

    // -------------------------------------------------------------------------
    // FAWE rollback
    // -------------------------------------------------------------------------

    private void copyRegionAsync() {
        if (!map.hasRollbackRegion()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(Bukkit.getWorld(map.getWorldName()));
                if (weWorld == null) return;

                BlockVector3 pos1 = BlockVector3.at(
                        (int) map.getCorner1X(), (int) map.getCorner1Y(), (int) map.getCorner1Z());
                BlockVector3 pos2 = BlockVector3.at(
                        (int) map.getCorner2X(), (int) map.getCorner2Y(), (int) map.getCorner2Z());

                CuboidRegion region = new CuboidRegion(weWorld, pos1, pos2);
                BlockArrayClipboard cb = new BlockArrayClipboard(region);

                try (EditSession session = WorldEdit.getInstance().newEditSession(weWorld)) {
                    ForwardExtentCopy copy = new ForwardExtentCopy(session, region, cb, region.getMinimumPoint());
                    copy.setCopyingBiomes(true);
                    copy.setCopyingEntities(false);
                    Operations.complete(copy);
                } catch (WorldEditException e) {
                    e.printStackTrace();
                    return;
                }

                this.clipboard = cb;
                this.clipboardReady = true;
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void pasteRegionAsync(Runnable onComplete) {
        if (!map.hasRollbackRegion() || !clipboardReady || clipboard == null) {
            clearPlacedBlocks();
            if (onComplete != null) {
                Bukkit.getScheduler().runTask(plugin, onComplete);
            }
            return;
        }

        placedBlocks.clear();
        cleanupEntitiesInRegion();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(Bukkit.getWorld(map.getWorldName()));
                if (weWorld == null) {
                    if (onComplete != null) Bukkit.getScheduler().runTask(plugin, onComplete);
                    return;
                }

                try (EditSession editSession = WorldEdit.getInstance().newEditSession(weWorld)) {
                    Operation operation = new ClipboardHolder(clipboard)
                            .createPaste(editSession)
                            .to(clipboard.getMinimumPoint())
                            .copyEntities(false)
                            .copyBiomes(true)
                            .build();
                    Operations.complete(operation);
                } catch (WorldEditException e) {
                    e.printStackTrace();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (onComplete != null) {
                    Bukkit.getScheduler().runTask(plugin, onComplete);
                }
            }
        });
    }

    private void cleanupEntitiesInRegion() {
        World world = Bukkit.getWorld(map.getWorldName());
        if (world == null) return;

        double minX = Math.min(map.getCorner1X(), map.getCorner2X());
        double maxX = Math.max(map.getCorner1X(), map.getCorner2X());
        double minZ = Math.min(map.getCorner1Z(), map.getCorner2Z());
        double maxZ = Math.max(map.getCorner1Z(), map.getCorner2Z());

        for (Entity entity : world.getEntities()) {
            if (entity instanceof Player) continue;
            Location loc = entity.getLocation();
            if (loc.getX() >= minX && loc.getX() <= maxX && loc.getZ() >= minZ && loc.getZ() <= maxZ) {
                entity.remove();
            }
        }
    }

    // -------------------------------------------------------------------------

    private void clearPlacedBlocks() {
        for (Location loc : placedBlocks) {
            loc.getBlock().setType(Material.AIR);
        }
        placedBlocks.clear();
    }

    public void addPlacedBlock(Location location) {
        placedBlocks.add(location.toBlockLocation());
    }

    public boolean isPlacedBlock(Location location) {
        return placedBlocks.contains(location.toBlockLocation());
    }

    public void removePlacedBlock(Location location) {
        placedBlocks.remove(location.toBlockLocation());
    }

    public void addSpectator(UUID uuid) {
        spectators.add(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && map.getSpectatorSpawn() != null) {
            player.teleport(map.getSpectatorSpawn());
        }
    }

    public DuelTeam getTeam(UUID uuid) {
        if (team1.hasPlayer(uuid)) return team1;
        if (team2.hasPlayer(uuid)) return team2;
        return null;
    }

    public DuelTeam getOpponent(DuelTeam team) {
        return team == team1 ? team2 : team1;
    }

    public Set<UUID> getAllDuelPlayers() {
        Set<UUID> all = new HashSet<>(team1.getPlayers());
        all.addAll(team2.getPlayers());
        return all;
    }

    public Set<UUID> getAllParticipants() {
        Set<UUID> all = getAllDuelPlayers();
        all.addAll(spectators);
        return all;
    }

    public boolean isInDuel(UUID uuid) {
        return team1.hasPlayer(uuid) || team2.hasPlayer(uuid);
    }

    public boolean isSpectator(UUID uuid) {
        return spectators.contains(uuid);
    }

    private void broadcastActionBar(String message) {
        for (UUID uuid : getAllParticipants()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                MessageUtil.sendActionBar(p, message);
            }
        }
    }

    private void broadcastMessage(Component message) {
        for (UUID uuid : getAllParticipants()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.sendMessage(message);
            }
        }
    }

    private enum SoundType {
        COUNTDOWN_TICK, COUNTDOWN_GO
    }

    private void broadcastSound(SoundType type) {
        for (UUID uuid : getAllParticipants()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                switch (type) {
                    case COUNTDOWN_TICK -> MessageUtil.playCountdownTick(p);
                    case COUNTDOWN_GO -> MessageUtil.playCountdownGo(p);
                }
            }
        }
    }

    private void configureWorldRules() {
        World world = Bukkit.getWorld(map.getWorldName());
        if (world == null) return;
        originalDoDaylightCycle = world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE);
        originalDoMobSpawning = world.getGameRuleValue(GameRule.DO_MOB_SPAWNING);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.LOCATOR_BAR, false);
        world.setStorm(false);
        world.setThundering(false);
    }

    private void restoreWorldRules() {
        boolean otherDuelInWorld = false;
        for (Duel other : manager.getActiveDuels()) {
            if (other != this && other.getMap().getWorldName().equals(map.getWorldName())
                    && other.getState() != DuelState.ENDED) {
                otherDuelInWorld = true;
                break;
            }
        }
        if (otherDuelInWorld) return;

        World world = Bukkit.getWorld(map.getWorldName());
        if (world == null) return;
        if (originalDoDaylightCycle != null)
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, originalDoDaylightCycle);
        if (originalDoMobSpawning != null)
            world.setGameRule(GameRule.DO_MOB_SPAWNING, originalDoMobSpawning);
    }

    public DuelState getState() {
        return state;
    }

    public Kit getKit() {
        return kit;
    }

    public ArenaMap getMap() {
        return map;
    }

    public DuelTeam getTeam1() {
        return team1;
    }

    public DuelTeam getTeam2() {
        return team2;
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public int getTotalRounds() {
        return totalRounds;
    }

    public Set<UUID> getSpectators() {
        return spectators;
    }

    public Set<Location> getPlacedBlocks() {
        return placedBlocks;
    }

    public void forceEnd() {
        if (state == DuelState.ENDED) return;
        state = DuelState.ENDED;
        if (activeTask != null) {
            activeTask.cancel();
        }
        clearPlacedBlocks();
        restoreWorldRules();
        manager.endDuel(this);

        for (UUID uuid : getAllParticipants()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) plugin.getLobbyManager().sendToLobby(p);
        }
    }
}
