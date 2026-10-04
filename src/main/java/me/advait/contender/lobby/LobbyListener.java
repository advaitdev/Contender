package me.advait.contender.lobby;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import me.advait.contender.Contender;
import me.advait.contender.util.Tags;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.*;

import java.util.concurrent.ExecutionException;
import java.util.logging.Level;

/**
 * Lobby world protection, plus build protection everywhere else: outside a game, players can only break or
 * place blocks when Lobby Settings allows it or they have the admin build bypass. Players in a match or
 * minigame follow that game's rules (its kit's Break Blocks and Place Blocks) instead.
 */
public final class LobbyListener implements Listener {
    private final Contender plugin;
    private final LobbyService lobby;

    public LobbyListener(Contender plugin, LobbyService lobby) {
        this.plugin = plugin;
        this.lobby = lobby;
    }

    /** Players in a match or minigame follow that game's rules. Interviews, vote reveals and course building don't. */
    private boolean exempt(Entity entity) {
        if (entity instanceof Player player) {
            var owner = plugin.getRegistry().owner(player.getUniqueId());
            return plugin.getRegistry().isPlaying(player.getUniqueId())
                    && (owner instanceof me.advait.contender.duel.Duel || owner instanceof me.advait.contender.minigame.Minigame);
        }
        return Tags.isManaged(entity);
    }

    private boolean mayBreak(Player player) { return exempt(player) || lobby.canBreak(player); }
    private boolean mayPlace(Player player) { return exempt(player) || lobby.canPlace(player); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        try {
            var id = event.getConnection().getProfile().getId();
            var location = plugin.getServer().getScheduler().callSyncMethod(plugin, () -> {
                if (!lobby.teleportOnJoin() || id == null) return null;
                if (!plugin.getRegistry().isFree(id)) return null;
                return lobby.location();
            }).get();
            if (location != null) event.setSpawnLocation(location);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not choose the lobby for a joining player.", failure.getCause());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !plugin.getRegistry().isFree(player.getUniqueId())) return;
            if (plugin.getSnapshots().has(player.getUniqueId())) return; // The registry restores them at the lobby.
            if (lobby.teleportOnJoin()) lobby.send(player);
            else lobby.applyRoleMode(player);
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent event) {
        if (!mayBreak(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamageBlock(BlockDamageEvent event) {
        if (!mayBreak(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent event) {
        if (!mayPlace(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!mayPlace(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!mayBreak(event.getPlayer())) event.setCancelled(true);
    }

    /**
     * Without build rights, players can't change anything by clicking or stepping on it: no trapdoors, doors,
     * gates, buttons, levers, pressure plates, beds, note blocks, chests or decorations, and no tools that
     * reshape blocks. Signs still work for clicking (editing them is blocked separately).
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        var block = event.getClickedBlock();
        if (block == null) return;
        Material type = block.getType();
        if (event.getAction() == Action.PHYSICAL) {
            // Trampling farmland breaks it; plates, tripwires and eggs set things off.
            if (type == Material.FARMLAND) { if (!mayBreak(player)) event.setCancelled(true); return; }
            if (!mayChange(player) && (org.bukkit.Tag.PRESSURE_PLATES.isTagged(type) || type == Material.TRIPWIRE
                    || type == Material.TURTLE_EGG || type == Material.SNIFFER_EGG || type == Material.BIG_DRIPLEAF)) event.setCancelled(true);
            return;
        }
        if (mayChange(player)) return;
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            // Hitting a note block plays it, and hitting the dragon egg sends it flying.
            if (type == Material.NOTE_BLOCK || type == Material.DRAGON_EGG) event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        var state = block.getState(false);
        boolean sign = org.bukkit.Tag.ALL_SIGNS.isTagged(type);
        if (!sign && (type.isInteractable() || type == Material.FLOWER_POT || type.name().startsWith("POTTED_")
                || state instanceof org.bukkit.block.Container || state instanceof org.bukkit.block.DecoratedPot)) {
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        }
        if (reshapes(event.getItem())) event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
    }

    /** May click, step on and reshape blocks: playing a game, or allowed to build here. */
    private boolean mayChange(Player player) { return exempt(player) || lobby.canBreak(player) && lobby.canPlace(player); }

    /** Items that change the block they're used on: stripping, tilling, paths, waxing, brushing, mud, carving, or a spawn egg. */
    private static boolean reshapes(org.bukkit.inventory.ItemStack item) {
        if (item == null) return false;
        Material type = item.getType();
        return org.bukkit.Tag.ITEMS_AXES.isTagged(type) || org.bukkit.Tag.ITEMS_HOES.isTagged(type) || org.bukkit.Tag.ITEMS_SHOVELS.isTagged(type)
                || type == Material.HONEYCOMB || type == Material.BRUSH || type == Material.SHEARS || type == Material.POTION
                || type == Material.GLASS_BOTTLE || type == Material.BONE_MEAL || type.name().endsWith("_SPAWN_EGG");
    }

    /** Boats, minecarts, armor stands and end crystals can't be put down without build rights either. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getPlayer() != null && !mayPlace(event.getPlayer())) event.setCancelled(true);
    }

    /** Mobs and arrows don't set off plates and tripwires in the lobby. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityInteract(EntityInteractEvent event) {
        if (lobby.isLobbyWorld(event.getBlock().getWorld()) && !(event.getEntity() instanceof Player) && !Tags.isManaged(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSignEdit(io.papermc.paper.event.player.PlayerOpenSignEvent event) {
        if (!mayPlace(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSignChange(org.bukkit.event.block.SignChangeEvent event) {
        if (!mayPlace(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFertilize(org.bukkit.event.block.BlockFertilizeEvent event) {
        if (event.getPlayer() != null) { if (!mayPlace(event.getPlayer())) event.setCancelled(true); return; }
        if (lobby.isLobbyWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (exempt(event.getPlayer()) || lobby.canBreak(event.getPlayer()) && lobby.canPlace(event.getPlayer())) return;
        if (event.getRightClicked() instanceof ArmorStand || event.getRightClicked() instanceof ItemFrame) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onArmorStand(org.bukkit.event.player.PlayerArmorStandManipulateEvent event) {
        if (exempt(event.getPlayer()) || lobby.canBreak(event.getPlayer()) && lobby.canPlace(event.getPlayer())) return;
        event.setCancelled(true);
    }

    /** Outside the lobby world, players can't break armor stands, item frames or paintings either. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDecorationDamage(EntityDamageByEntityEvent event) {
        Entity entity = event.getEntity();
        if (lobby.isLobbyWorld(entity.getWorld()) || !(entity instanceof ArmorStand || entity instanceof org.bukkit.entity.Hanging)) return;
        Player attacker = event.getDamager() instanceof Player player ? player
                : event.getDamager() instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Player player ? player : null;
        if (attacker != null && !mayBreak(attacker)) event.setCancelled(true);
    }

    /** No damage in the lobby, except a creative player attacking for testing. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (!lobby.isLobbyWorld(entity.getWorld()) || exempt(entity)) return;
        if (entity instanceof Player && event instanceof EntityDamageByEntityEvent shot
                && shot.getDamager() instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Player shooter
                && !shooter.equals(entity)) return;
        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player attacker) {
            if (exempt(attacker)) return;
            // Player against player follows the PvP settings, which PvPListener has already applied.
            if (entity instanceof Player) return;
            if (entity instanceof ArmorStand || entity instanceof ItemFrame) {
                if (lobby.canBreak(attacker)) return;
            } else if (attacker.getGameMode() == GameMode.CREATIVE) return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID || event.getCause() == EntityDamageEvent.DamageCause.KILL) {
            if (entity instanceof Player player && event.getCause() == EntityDamageEvent.DamageCause.VOID) {
                event.setCancelled(true);
                plugin.getServer().getScheduler().runTask(plugin, () -> lobby.send(player));
            }
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && lobby.isLobbyWorld(player.getWorld()) && !exempt(player)) event.setCancelled(true);
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (lobby.isLobbyWorld(event.getEntity().getWorld())) event.blockList().clear();
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        if (lobby.isLobbyWorld(event.getBlock().getWorld())) event.blockList().clear();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        boolean lobbyWorld = lobby.isLobbyWorld(event.getBlock().getWorld());
        if (event.getEntity() instanceof Player player) {
            // Outside the lobby world, a game's own rules decide for its players.
            if (!lobby.canBreak(player) && !(exempt(player) && !lobbyWorld)) event.setCancelled(true);
            return;
        }
        if (lobbyWorld) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFlow(BlockFromToEvent event) {
        if (lobby.isLobbyWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler
    public void onBurn(BlockBurnEvent event) {
        if (lobby.isLobbyWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler
    public void onIgnite(BlockIgniteEvent event) {
        if (event.getPlayer() != null) { if (!mayPlace(event.getPlayer())) event.setCancelled(true); return; }
        if (lobby.isLobbyWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler
    public void onSpread(BlockSpreadEvent event) {
        if (lobby.isLobbyWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHangingBreak(HangingBreakEvent event) {
        if (event instanceof HangingBreakByEntityEvent broken && broken.getRemover() instanceof Player player) {
            if (!mayBreak(player)) event.setCancelled(true);
            return;
        }
        if (!lobby.isLobbyWorld(event.getEntity().getWorld())) return;
        if (event.getCause() == HangingBreakEvent.RemoveCause.PHYSICS && (lobby.allowBlockBreak() || lobby.adminBuildBypass())) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onLeafDecay(LeavesDecayEvent event) { if (lobby.isLobbyWorld(event.getBlock().getWorld())) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onNaturalSpawn(CreatureSpawnEvent event) {
        if (lobby.isLobbyWorld(event.getLocation().getWorld()) && event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) {
            event.setCancelled(true);
        }
    }
}
