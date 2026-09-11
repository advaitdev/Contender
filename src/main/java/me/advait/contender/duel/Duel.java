package me.advait.contender.duel;

import me.advait.contender.Contender;
import me.advait.contender.arena.ArenaLease;
import me.advait.contender.player.PlayerSettingsManager;
import me.advait.contender.kit.Kit;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.spectator.SpectatorVisibility;
import me.advait.contender.util.MessageUtil;
import me.advait.contender.util.StringUtil;
import me.libraryaddict.disguise.DisguiseAPI;
import me.libraryaddict.disguise.disguisetypes.DisguiseType;
import me.libraryaddict.disguise.disguisetypes.MobDisguise;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mannequin;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class Duel {

    private final Contender plugin;
    private final DuelManager manager;
    private final Kit kit;
    private final ArenaMap map;
    private final ArenaLease arena;
    private final boolean tournamentDuel;
    private DuelResult.Reason resultReason = DuelResult.Reason.CANCELLED;
    private Integer forfeitWinner;
    private final DuelTeam team1;
    private final DuelTeam team2;
    private final int totalRounds;
    private final int preRoundDelay;
    private final DuelMode mode;
    private final Set<UUID> spectators;
    private final Set<UUID> deadPlayerSpectators;
    private final Set<Location> placedBlocks;
    private final Map<UUID, InventorySnapshot> sortedInventories;
    private final Map<UUID, InventorySnapshot> preDuelInventories;
    private final SpectatorVisibility spectatorVisibility;

    private AbstractDuelState state;
    private boolean started;
    private boolean participantsRestored;
    private int currentRound;
    private BukkitTask spectatorVisibilityTask;
    private boolean firstRound;
    private BossBar bossBar;
    private final Map<Entity, BukkitTask> deathEffects = new HashMap<>();


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
        this(plugin, manager, setup, null, false);
    }

    public Duel(Contender plugin, DuelManager manager, DuelSetup setup, ArenaLease arena, boolean tournamentDuel) {
        this.plugin = plugin;
        this.manager = manager;
        this.kit = setup.getSelectedKit();
        this.arena = arena;
        this.tournamentDuel = tournamentDuel;
        this.map = arena == null ? setup.getSelectedMap() : arena.instance().map();

        this.team1 = new DuelTeam("Team 1");
        if (setup.getTeam1().hasCustomName()) this.team1.setName(setup.getTeam1().getName());
        for (UUID uuid : setup.getTeam1().getPlayers()) {
            this.team1.addPlayer(uuid);
        }

        this.team2 = new DuelTeam("Team 2");
        if (setup.getTeam2().hasCustomName()) this.team2.setName(setup.getTeam2().getName());
        for (UUID uuid : setup.getTeam2().getPlayers()) {
            this.team2.addPlayer(uuid);
        }

        this.mode = setup.getMode();
        this.totalRounds = setup.getRounds();
        this.preRoundDelay = setup.getPreRoundDelay();
        this.spectators = new HashSet<>();
        this.deadPlayerSpectators = new HashSet<>();
        this.placedBlocks = new HashSet<>();
        this.sortedInventories = new HashMap<>();
        this.preDuelInventories = new HashMap<>();
        this.spectatorVisibility = new SpectatorVisibility(plugin);
        this.state = new StartingState(this);
        this.currentRound = 0;
        this.firstRound = true;
    }

    public void start() {
        if (started || isFinished()) return;
        started = true;
        enableState();
    }

    public void setState(AbstractDuelState next) {
        Objects.requireNonNull(next);
        if (next.duel != this) throw new IllegalArgumentException("State belongs to another duel");
        if (state == next || isFinished()) return;
        state.disable();
        state = next;
        if (!next.isFinished()) enableState();
    }

    private void enableState() {
        try {
            state.enable();
        } catch (RuntimeException | Error failure) {
            shutdown();
            throw failure;
        }
    }

    boolean prepareStart() {
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
            return false;
        }

        // Capture pre-duel inventories for No Clear kit before any changes
        if (kit.isNoClear()) {
            for (UUID uuid : getAllDuelPlayers()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    preDuelInventories.put(uuid, new InventorySnapshot(
                            player.getInventory().getStorageContents(),
                            player.getInventory().getArmorContents(),
                            player.getInventory().getItemInOffHand()
                    ));
                }
            }
        }

        for (UUID uuid : team1.getPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.setGameMode(GameMode.SURVIVAL);
                player.teleport(map.getTeam1Spawn());
                if (kit.isNoClear()) kit.applyEffectsOnly(player);
                else kit.apply(player);
            }
        }

        for (UUID uuid : team2.getPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.setGameMode(GameMode.SURVIVAL);
                player.teleport(map.getTeam2Spawn());
                if (kit.isNoClear()) kit.applyEffectsOnly(player);
                else kit.apply(player);
            }
        }

        Location specSpawn = map.getSpectatorSpawn();
        for (UUID uuid : spectators) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                if (specSpawn != null) {
                    player.teleport(specSpawn);
                }
                applySpectatorDisguise(player);
            }
        }

        startSpectatorVisibilityTask();

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
        bossBar = BossBar.bossBar(buildBossBarTitle(), 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);

        for (UUID uuid : getAllParticipants()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.sendMessage(vsMessage);
                p.showBossBar(bossBar);
            }
        }

        return true;
    }

    private String buildTeamNames(DuelTeam team) {
        if (team.hasCustomName()) return MiniMessage.miniMessage().escapeTags(team.getName());
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

    private Component buildBossBarTitle() {
        if (mode == DuelMode.FFA) {
            return MiniMessage.miniMessage().deserialize(
                    "<font:minecraft:ranyth><color:#FFFFFF><shadow:#2F2C2C:1>Free For All</shadow></color></font>");
        }
        MiniMessage mm = MiniMessage.miniMessage();
        String team1Names = buildTeamNames(team1);
        String team2Names = buildTeamNames(team2);

        List<UUID> t1Players = team1.getPlayers();
        List<UUID> t2Players = team2.getPlayers();

        Player first1 = t1Players.isEmpty() ? null : Bukkit.getPlayer(t1Players.getFirst());
        Player first2 = t2Players.isEmpty() ? null : Bukkit.getPlayer(t2Players.getFirst());
        Component head1 = first1 == null ? Component.empty() : StringUtil.getPlayerHead(first1);
        Component head2 = first2 == null ? Component.empty() : StringUtil.getPlayerHead(first2);

        Component part1 = mm.deserialize(
                "<font:minecraft:ranyth><color:#FFFFFF><shadow:#2F2C2C:1>" + team1Names + " vs </shadow></color></font>");
        Component part2 = mm.deserialize(
                "<font:minecraft:ranyth><color:#FFFFFF><shadow:#2F2C2C:1>" + team2Names + "</shadow></color></font>");

        return Component.empty()
                .append(head1).appendSpace()
                .append(part1)
                .append(head2).appendSpace()
                .append(part2);
    }

    void captureInventories() {
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
                player.updateInventory();
            } else {
                kit.apply(player);
            }

            player.setHealth(20.0);
            player.setFoodLevel(20);
            player.setSaturation(5.0f);
            player.setLevel(0);
            player.setExp(0);
            player.setFireTicks(0);
            player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        }
    }

    void prepareRound() {
        currentRound++;

        // Remove disguises from any dead players spectating before resetting them
        for (UUID deadSpec : new HashSet<>(deadPlayerSpectators)) {
            Player p = Bukkit.getPlayer(deadSpec);
            if (p != null) removeSpectatorDisguise(p);
        }
        deadPlayerSpectators.clear();

        team1.resetAlive();
        team2.resetAlive();

        if (mode == DuelMode.FFA) {
            // Randomly distribute all players between the two spawn points
            List<UUID> allPlayers = new ArrayList<>(getAllDuelPlayers());
            Collections.shuffle(allPlayers);
            for (int i = 0; i < allPlayers.size(); i++) {
                Player player = Bukkit.getPlayer(allPlayers.get(i));
                if (player == null) continue;
                player.setGameMode(GameMode.SURVIVAL);
                player.teleport(i % 2 == 0 ? map.getTeam1Spawn() : map.getTeam2Spawn());
            }
        } else {
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
        }

        if (!firstRound) {
            restoreInventories();
        }
        firstRound = false;
        updateSpectatorVisibility();
    }

    void announceRound() {
        broadcastActionBar("<color:" + MessageUtil.PRIMARY + ">Round " + currentRound + "/" + totalRounds
                + " <color:" + MessageUtil.SECONDARY + ">(" + team1.getScore() + " - "
                + team2.getScore() + ")</color></color>");
    }

    public void handleDeath(Player deadPlayer, Player killer) {
        if (!isCombatActive()) return;

        UUID deadUuid = deadPlayer.getUniqueId();
        DuelTeam deadTeam = getTeam(deadUuid);
        if (deadTeam == null) return;

        // Guard against double-firing (e.g. EntityDamageEvent interception + PlayerDeathEvent fallback)
        if (!deadTeam.getAlivePlayers().contains(deadUuid)) return;
        deadTeam.markDead(deadUuid);

        // Put the dead player into flying-disguise spectator mode
        deadPlayerSpectators.add(deadUuid);
        deadPlayer.getInventory().clear();
        deadPlayer.setGameMode(GameMode.ADVENTURE);
        applySpectatorDisguise(deadPlayer);

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

        if (mode == DuelMode.FFA) {
            int alive = team1.getAlivePlayers().size() + team2.getAlivePlayers().size();
            if (alive <= 1) {
                DuelTeam winner = !team1.getAlivePlayers().isEmpty() ? team1
                        : !team2.getAlivePlayers().isEmpty() ? team2
                        : null;
                endRound(winner);
            }
        } else {
            if (deadTeam.isEliminated()) {
                endRound(getOpponent(deadTeam));
            }
        }
    }

    private void endRound(DuelTeam winner) {
        setState(new RoundEndState(this, winner));
    }

    public void endDuel() {
        if (state.isEnding() || isFinished()) return;
        resultReason = DuelResult.Reason.FINISHED;
        setState(new EndingState(this, false));
    }

    void announceResult() {
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
            String winnerName = mm.escapeTags(winner.getName());
            String msg = MessageUtil.FONT_OPEN + "<color:" + MessageUtil.PRIMARY + ">" + winnerName + " wins the duel! " +
                    "<color:" + MessageUtil.SECONDARY + ">" + team1.getScore() + " - " + team2.getScore() + "</color></color>" + MessageUtil.FONT_CLOSE;
            broadcastActionBar("<color:" + MessageUtil.PRIMARY + ">" + winnerName + " wins the duel! " +
                    "<color:" + MessageUtil.SECONDARY + ">" + team1.getScore() + " - " + team2.getScore() + "</color></color>");
            broadcastMessage(mm.deserialize(msg));

            for (UUID uuid : winner.getPlayers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    //MessageUtil.playDuelEnd(p);
                }
            }
        }
    }

    void returnParticipantsToLobby() {
        if (participantsRestored) return;
        participantsRestored = true;
        stopSpectatorVisibility();
        for (UUID uuid : getAllParticipants()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                if (bossBar != null) player.hideBossBar(bossBar);
                if (isSpectator(uuid)) removeSpectatorDisguise(player);
                plugin.getLobbyManager().sendToLobby(player);
                restorePreDuelInventory(uuid, player);
            }
        }
        bossBar = null;
        clearDeathEffects();
    }

    void finish() {
        if (isFinished()) return;
        stopSpectatorVisibility();
        setState(new EndedState(this));
        manager.endDuel(this);
    }

    void rollbackArena(Runnable onComplete) {
        if (arena == null) {
            clearPlacedBlocks();
            cleanupEntitiesInRegion();
            onComplete.run();
            return;
        }
        plugin.getArenaManager().reset(arena).whenComplete((ignored, failure) -> {
            if (!plugin.isEnabled() || isFinished()) return;
            if (failure != null) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not reset arena " + arena.instance().slot(), failure);
                resultReason = DuelResult.Reason.CANCELLED;
                shutdown();
                return;
            }
            placedBlocks.clear();
            onComplete.run();
        });
    }

    private void cleanupEntitiesInRegion() {
        World world = Bukkit.getWorld(map.getWorldName());
        if (world == null) return;

        if (map.getBounds() == null) return;
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof Player) && map.contains(entity.getLocation())) entity.remove();
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
        if (player != null) {
            if (bossBar != null) {
                player.showBossBar(bossBar);
            }
            // Disguise and fly are applied in start() once the duel begins;
            // if added mid-duel (future-proof), apply immediately.
            if (state.canAddSpectator()) {
                if (map.getSpectatorSpawn() != null) {
                    player.teleport(map.getSpectatorSpawn());
                }
                applySpectatorDisguise(player);
            }
        }
    }

    public void removeSpectator(Player player) {
        if (!spectators.remove(player.getUniqueId())) return;
        removeSpectatorDisguise(player);
        if (bossBar != null) player.hideBossBar(bossBar);
        updateSpectatorVisibility();
        plugin.getLobbyManager().sendToLobby(player);
    }

    public void forfeit(UUID player) {
        if (!tournamentDuel) { forceEnd(); return; }
        if (state.isEnding() || isFinished()) return;
        DuelTeam leaving = getTeam(player);
        if (leaving == null) return;
        forfeitWinner = leaving == team1 ? 2 : 1;
        resultReason = DuelResult.Reason.FORFEIT;
        broadcastMessage(Component.text(getOpponent(leaving).getName() + " wins by forfeit."));
        setState(new EndingState(this, true));
    }

    public ArenaLease getArena() { return arena; }
    public DuelResult getResult() {
        Integer winner = forfeitWinner != null ? forfeitWinner
                : team1.getScore() == team2.getScore() ? null : team1.getScore() > team2.getScore() ? 1 : 2;
        return new DuelResult(resultReason, team1.getScore(), team2.getScore(), winner);
    }

    public DuelMode getMode() { return mode; }

    public boolean isInvincibilityActive() {
        return state instanceof ActiveState active && active.isInvincibilityActive();
    }

    private void restorePreDuelInventory(UUID uuid, Player player) {
        InventorySnapshot snap = preDuelInventories.get(uuid);
        if (snap == null) return;
        // sendToLobby already cleared inventory; restore original contents on top
        player.getInventory().setStorageContents(cloneArray(snap.storage()));
        player.getInventory().setArmorContents(cloneArray(snap.armor()));
        if (snap.offhand() != null) player.getInventory().setItemInOffHand(snap.offhand().clone());
        player.updateInventory();
    }

    /** Called after a real respawn (vanilla death path) to re-apply the disguise-spectator state. */
    public void applyDeadSpectatorMode(Player player) {
        applySpectatorDisguise(player);
    }

    private void applySpectatorDisguise(Player player) {
        player.setAllowFlight(true);
        player.setFlying(true);

        player.setCollidable(!kit.isSpectatorInvisible());

        if (Bukkit.getPluginManager().isPluginEnabled("LibsDisguises")) {
            PlayerSettingsManager.SpectatorDisguise pref =
                    plugin.getPlayerSettingsManager().getDisguise(player.getUniqueId());
            DisguiseType libsType = switch (pref) {
                case ALLAY -> DisguiseType.ALLAY;
                case BEE -> DisguiseType.BEE;
                case PARROT -> DisguiseType.PARROT;
                case BAT -> DisguiseType.BAT;
                case VEX -> DisguiseType.VEX;
                case HAPPY_GHAST -> DisguiseType.HAPPY_GHAST;
            };
            // Baby happy ghast: pass false for isAdult
            MobDisguise disguise = pref == PlayerSettingsManager.SpectatorDisguise.HAPPY_GHAST
                    ? new MobDisguise(libsType, false)
                    : new MobDisguise(libsType);
            me.advait.contender.nametag.DisguiseNameTags.apply(disguise, plugin.getNameTagManager().displayName(player));
            DisguiseAPI.disguiseToAll(player, disguise);
        }
        updateSpectatorVisibility();
    }

    private void removeSpectatorDisguise(Player player) {
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setCollidable(true);

        if (Bukkit.getPluginManager().isPluginEnabled("LibsDisguises")) {
            DisguiseAPI.undisguiseToAll(player);
        }
    }

    private void startSpectatorVisibilityTask() {
        spectatorVisibilityTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::updateSpectatorVisibility, 0L, 5L);
    }

    private void updateSpectatorVisibility() {
        if (participantsRestored || isFinished()) return;
        List<Player> contestants = new ArrayList<>();
        List<Player> watching = new ArrayList<>();
        for (UUID uuid : getAllParticipants()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            if (isSpectator(uuid)) watching.add(player);
            else contestants.add(player);
        }
        spectatorVisibility.update(contestants, watching, kit.isSpectatorInvisible());
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
        return spectators.contains(uuid) || deadPlayerSpectators.contains(uuid);
    }

    void broadcastActionBar(String message) {
        for (UUID uuid : getAllParticipants()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                MessageUtil.sendActionBar(p, message);
            }
        }
    }

    void broadcastMessage(Component message) {
        for (UUID uuid : getAllParticipants()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.sendMessage(message);
            }
        }
    }

    enum SoundType {
        COUNTDOWN_TICK, COUNTDOWN_GO
    }

    void broadcastSound(SoundType type) {
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

    public AbstractDuelState getState() {
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
        if (isFinished()) return;
        if (!plugin.isEnabled()) {
            shutdown();
            return;
        }
        if (state instanceof EndingState ending && ending.isForced()) return;
        resultReason = DuelResult.Reason.CANCELLED;
        setState(new EndingState(this, true));
    }

    /** Synchronous teardown for plugin disable; it must not schedule new plugin tasks. */
    public void shutdown() {
        if (isFinished()) return;
        state.disable();
        if (arena != null) plugin.getArenaManager().discard(arena);
        stopSpectatorVisibility();
        returnParticipantsToLobby();
        clearPlacedBlocks();
        cleanupEntitiesInRegion();
        finish();
    }

    private void stopSpectatorVisibility() {
        if (spectatorVisibilityTask != null) {
            spectatorVisibilityTask.cancel();
            spectatorVisibilityTask = null;
        }
        spectatorVisibility.clear();
    }

    void spawnDeathMannequin(Player player) {
        Location location = player.getLocation();
        World world = location.getWorld();
        if (world == null) return;
        Mannequin mannequin = (Mannequin) world.spawnEntity(location, EntityType.MANNEQUIN);
        mannequin.setProfile(ResolvableProfile.resolvableProfile(player.getPlayerProfile()));
        mannequin.setRotation(location.getYaw(), location.getPitch());
        mannequin.setImmovable(true);
        mannequin.setGravity(false);
        mannequin.damage(100);
        BukkitTask removal = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            mannequin.remove();
            deathEffects.remove(mannequin);
        }, 60L);
        deathEffects.put(mannequin, removal);
    }

    private void clearDeathEffects() {
        deathEffects.forEach((entity, task) -> {
            task.cancel();
            entity.remove();
        });
        deathEffects.clear();
    }

    public boolean isCombatActive() { return state.isEnabled() && state.isCombatPhase(); }
    public boolean isFinished() { return state.isFinished(); }
    public boolean hasParticipant(UUID uuid) { return manager.getDuel(uuid) == this; }
    public Contender getPlugin() { return plugin; }
    int getPreRoundDelay() { return preRoundDelay; }
}
