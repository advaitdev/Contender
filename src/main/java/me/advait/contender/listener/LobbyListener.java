package me.advait.contender.listener;

import me.advait.contender.Contender;
import me.advait.contender.LobbyManager;
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
import org.bukkit.event.player.*;

public class LobbyListener implements Listener {

    private final Contender plugin;
    private final LobbyManager lobbyManager;

    public LobbyListener(Contender plugin, LobbyManager lobbyManager) {
        this.plugin = plugin;
        this.lobbyManager = lobbyManager;
    }

    // ── Player join ──────────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // 1-tick delay ensures the player is fully initialised before teleporting
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) lobbyManager.sendToLobby(player);
        }, 1L);
    }

    // ── Block protection ─────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        if (lobbyManager.isLobbyWorld(event.getBlock().getWorld()) && !lobbyManager.isAllowBlockBreak()) {
            event.setCancelled(true);
        }
    }

    /** Suppresses the block-cracking animation before a break fires. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockDamageEarly(BlockDamageEvent event) {
        if (lobbyManager.isLobbyWorld(event.getBlock().getWorld()) && !lobbyManager.isAllowBlockBreak()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (lobbyManager.isLobbyWorld(event.getBlock().getWorld()) && !lobbyManager.isAllowBlockBreak()) {
            event.setCancelled(true);
        }
    }

    /** Prevents placing water / lava / powdered snow via bucket. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (lobbyManager.isLobbyWorld(event.getPlayer().getWorld()) && !lobbyManager.isAllowBlockBreak()) {
            event.setCancelled(true);
        }
    }

    /** Prevents scooping up source blocks with a bucket. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (lobbyManager.isLobbyWorld(event.getPlayer().getWorld()) && !lobbyManager.isAllowBlockBreak()) {
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
            event.setCancelled(true);
        }
    }
}
