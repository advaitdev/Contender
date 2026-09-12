package me.advait.contender.lobby;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import me.advait.contender.Contender;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.GameMode;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.*;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;

public class LobbyListener implements Listener {

    private final Contender plugin;
    private final LobbyManager lobbyManager;

    public LobbyListener(Contender plugin, LobbyManager lobbyManager) {
        this.plugin = plugin;
        this.lobbyManager = lobbyManager;
    }

    // ── Player join ──────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        try {
            // World lookup, config and duel ownership are all read on the server thread.
            var playerId = event.getConnection().getProfile().getId();
            var location = plugin.getServer().getScheduler().callSyncMethod(plugin,
                    () -> lobbyManager.getJoinLocation(playerId)).get();
            if (location != null) event.setSpawnLocation(location);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not select the lobby for a joining player.", failure.getCause());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean returningFromEvent = plugin.getMinigameManager() != null
                && (plugin.getMinigameManager().owns(player.getUniqueId()) || plugin.getMinigameManager().pendingReturn(player.getUniqueId()))
                || plugin.getRaceManager() != null && (plugin.getRaceManager().owns(player.getUniqueId()) || plugin.getRaceManager().pendingReturn(player.getUniqueId()));
        // Keep the join-time decision: recovery may finish before this delayed task runs.
        // 1-tick delay ensures the player is fully initialised before teleporting
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (returningFromEvent) return;
            if (plugin.getMinigameManager() != null && (plugin.getMinigameManager().owns(player.getUniqueId()) || plugin.getMinigameManager().pendingReturn(player.getUniqueId()))) return;
            if (plugin.getRaceManager() != null && (plugin.getRaceManager().owns(player.getUniqueId()) || plugin.getRaceManager().pendingReturn(player.getUniqueId()))) return;
            if (!player.isOnline() || plugin.getDuelManager().getDuel(player) != null) return;
            plugin.getRoleManager().applySpectatorRole(player);
            if (lobbyManager.isTeleportOnJoin()) lobbyManager.sendToLobby(player);
        }, 1L);
    }

    // ── Block protection ─────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        if (lobbyManager.isLobbyWorld(event.getBlock().getWorld()) && !lobbyManager.canBreak(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Suppresses the block-cracking animation before a break fires. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockDamageEarly(BlockDamageEvent event) {
        if (lobbyManager.isLobbyWorld(event.getBlock().getWorld()) && !lobbyManager.canBreak(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (lobbyManager.isLobbyWorld(event.getBlock().getWorld()) && !lobbyManager.canPlace(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Prevents placing water / lava / powdered snow via bucket. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (lobbyManager.isLobbyWorld(event.getBlock().getWorld()) && !lobbyManager.canPlace(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Prevents scooping up source blocks with a bucket. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (lobbyManager.isLobbyWorld(event.getBlock().getWorld()) && !lobbyManager.canBreak(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // ── Edge-case interactions ───────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        var block = event.getClickedBlock();
        if (block == null) return;
        if (!lobbyManager.isLobbyWorld(block.getWorld())) return;
        if (lobbyManager.canBreak(event.getPlayer()) && lobbyManager.canPlace(event.getPlayer())) return;

        Material type = block.getType();

        // Right-clicking a filled flower pot removes the plant
        if (type == Material.FLOWER_POT || type.name().startsWith("POTTED_")) {
            event.setCancelled(true);
            return;
        }

        // Right-clicking cake consumes it and raises food level
        if (type == Material.CAKE || type.name().contains("CANDLE_CAKE")) {
            event.setCancelled(true);
        }
    }

    /** Prevents swapping / equipping items on armor stands and item frames. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!lobbyManager.isLobbyWorld(event.getRightClicked().getWorld())) return;
        if (lobbyManager.canBreak(event.getPlayer()) && lobbyManager.canPlace(event.getPlayer())) return;
        var entity = event.getRightClicked();
        if (entity instanceof ArmorStand || entity instanceof ItemFrame) {
            event.setCancelled(true);
        }
    }

    // ── Combat / survival ────────────────────────────────────────────────────

    /** Cancels all damage in the lobby world, except creative-mode players attacking survival players. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!lobbyManager.isLobbyWorld(event.getEntity().getWorld())) return;
        if (event.getEntity() instanceof ArmorStand || event.getEntity() instanceof ItemFrame) {
            if (event instanceof EntityDamageByEntityEvent attack && attack.getDamager() instanceof Player player
                    && lobbyManager.canBreak(player)) return;
            event.setCancelled(true);
            return;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof Player attacker
                && attacker.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        event.setCancelled(true);
    }

    /** Keeps hunger frozen at full so players never starve. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (lobbyManager.isLobbyWorld(player.getWorld())) {
            event.setCancelled(true);
        }
    }

    // ── World/environment protection ─────────────────────────────────────────

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (lobbyManager.isLobbyWorld(event.getEntity().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        if (lobbyManager.isLobbyWorld(event.getBlock().getWorld())) {
            event.blockList().clear();
        }
    }

    /** Mob griefing: endermen picking blocks, silverfish infesting, ravagers, etc. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (lobbyManager.isLobbyWorld(event.getBlock().getWorld())) {
            if (event.getEntity() instanceof Player player && lobbyManager.canBreak(player)) return;
            event.setCancelled(true);
        }
    }

    /** Prevents water / lava from flowing through the lobby. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockFromTo(BlockFromToEvent event) {
        if (lobbyManager.isLobbyWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBurn(BlockBurnEvent event) {
        if (lobbyManager.isLobbyWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (lobbyManager.isLobbyWorld(event.getBlock().getWorld())) {
            if (event.getPlayer() != null && lobbyManager.canPlace(event.getPlayer())) return;
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockSpread(BlockSpreadEvent event) {
        if (lobbyManager.isLobbyWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    /** Prevents paintings and item frames from breaking when their block is altered. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onHangingBreak(HangingBreakEvent event) {
        if (lobbyManager.isLobbyWorld(event.getEntity().getWorld())) {
            if (event instanceof HangingBreakByEntityEvent broken && broken.getRemover() instanceof Player player
                    && lobbyManager.canBreak(player)) return;
            // Let decorations detach when an allowed builder removes their supporting block.
            if (event.getCause() == HangingBreakEvent.RemoveCause.PHYSICS
                    && (lobbyManager.isAllowBlockBreak() || lobbyManager.isAdminBuildBypass())) return;
            event.setCancelled(true);
        }
    }
}
