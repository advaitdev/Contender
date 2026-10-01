package me.advait.contender.minigame.manhunt;

import me.advait.contender.Contender;
import me.advait.contender.core.Msg;
import me.advait.contender.core.Sounds;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.duel.HurtRules;
import me.advait.contender.kit.Kit;
import me.advait.contender.minigame.Minigame;
import me.advait.contender.tab.StandingsLayout;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Manhunt in a fresh End: runners win when the dragon dies, hunters win when every runner is out.
 * Everyone has one life; anyone who dies keeps watching.
 */
public final class ManhuntGame extends Minigame {
    public enum Team {
        RUNNER("Runner", "Runners"), HUNTER("Hunter", "Hunters");
        final String one, many;
        Team(String one, String many) { this.one = one; this.many = many; }
    }

    private final ManhuntType manhunt;
    private final Kit kit;
    private final Map<UUID, Team> teams;
    private final String worldName;
    private final Map<UUID, Boolean> collisions = new HashMap<>();
    private final Map<UUID, Location> spawns = new HashMap<>();
    private Team winner;
    private int missingDragonTicks;

    ManhuntGame(Contender plugin, ManhuntType type, UUID id, String name, Map<UUID, String> players, Kit kit, Map<UUID, Team> teams, String worldName) {
        super(plugin, type, id, name, players);
        this.manhunt = type;
        this.kit = kit;
        this.teams = new LinkedHashMap<>(teams);
        this.worldName = worldName;
    }

    public Team team(UUID player) { return teams.getOrDefault(player, Team.HUNTER); }

    void restoreWinner(String saved) {
        try { winner = saved == null ? null : Team.valueOf(saved); } catch (IllegalArgumentException ignored) { }
    }

    private ManhuntWorld end() { return manhunt.world(); }

    @Override protected int minimumPlayers() { return 2; }

    private long alive(Team team) {
        return roster().stream().filter(p -> p.status == Status.PLAYING && team(p.id) == team).count();
    }

    // ---- Lifecycle -----------------------------------------------------------------------------

    @Override protected CompletableFuture<Void> prepare() {
        if (!end().ready() || !end().name().equals(worldName)) {
            return CompletableFuture.failedFuture(new IllegalStateException("This game's End was replaced or used. Prepare a fresh End and create the game again."));
        }
        boolean runner = false, hunter = false;
        for (Participant participant : roster()) {
            if (participant.status == Status.WITHDRAWN || Bukkit.getPlayer(participant.id) == null) continue;
            if (team(participant.id) == Team.RUNNER) runner = true; else hunter = true;
        }
        if (!runner || !hunter) return CompletableFuture.failedFuture(new IllegalStateException("Each team needs an online player."));
        end().use();
        return CompletableFuture.completedFuture(null);
    }

    @Override protected void setup(List<Player> players) {
        // Alternate teams around the pillar ring so runners don't all start side by side.
        List<Player> runners = players.stream().filter(p -> team(p.getUniqueId()) == Team.RUNNER).toList();
        List<Player> hunters = players.stream().filter(p -> team(p.getUniqueId()) == Team.HUNTER).toList();
        List<Player> order = new ArrayList<>();
        for (int i = 0; i < Math.max(runners.size(), hunters.size()); i++) {
            if (i < runners.size()) order.add(runners.get(i));
            if (i < hunters.size()) order.add(hunters.get(i));
        }
        var theme = plugin.getThemes().current();
        for (int i = 0; i < order.size(); i++) {
            Player player = order.get(i);
            Location spawn = end().spawn(i);
            spawns.put(player.getUniqueId(), spawn);
            collisions.put(player.getUniqueId(), player.isCollidable());
            player.setCollidable(false);
            player.setGameMode(GameMode.SURVIVAL);
            player.setAllowFlight(false);
            player.setFlying(false);
            player.teleport(spawn);
            player.setVelocity(new Vector());
            player.setFallDistance(0);
            player.setFireTicks(0);
            player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
            if (kit == null) { player.getInventory().clear(); player.setLevel(0); player.setExp(0); }
            else if (kit.isNoClear()) kit.applyEffectsOnly(player);
            else kit.apply(player);
            var max = player.getAttribute(Attribute.MAX_HEALTH);
            player.setHealth(max == null ? 20 : max.getValue());
            player.setFoodLevel(20);
            player.setSaturation(5);
            Team team = team(player.getUniqueId());
            Msg.title(player, Component.text(team == Team.RUNNER ? "You're a Runner" : "You're a Hunter", team == Team.RUNNER ? theme.primary() : theme.secondary()),
                    Msg.text(team == Team.RUNNER ? "Kill the dragon to win" : "Stop every runner", DialogPalette.MUTED), 4, 70, 10);
        }
        broadcast(Msg.text("Runners: " + names(Team.RUNNER), DialogPalette.ACCENT));
        broadcast(Msg.text("Hunters: " + names(Team.HUNTER), DialogPalette.TEXT));
    }

    private String names(Team team) {
        return String.join(", ", roster().stream().filter(p -> team(p.id) == team && p.status != Status.WITHDRAWN).map(p -> p.name).toList());
    }

    @Override protected void begin() {
        World world = end().world();
        if (world.getEntitiesByClass(EnderDragon.class).stream().noneMatch(Entity::isValid)) {
            EnderDragon dragon = world.spawn(new Location(world, 0, 110, 0), EnderDragon.class);
            dragon.setPhase(EnderDragon.Phase.CIRCLING);
        }
        end().thaw();
        collisions.forEach((id, value) -> { Player player = Bukkit.getPlayer(id); if (player != null) player.setCollidable(value); });
        collisions.clear();
        tasks.repeat(1, 1, this::step);
    }

    private void step() {
        if (winner != null) return;
        if (kit != null && kit.keepsHungerFull()) for (Player player : playing()) { player.setFoodLevel(20); player.setSaturation(20); }
        var dragons = end().world().getEntitiesByClass(EnderDragon.class);
        if (dragons.stream().anyMatch(d -> d.getHealth() <= 0 || d.getDeathAnimationTicks() > 0)) { runnersWin(); return; }
        if (dragons.stream().noneMatch(Entity::isValid)) {
            if (++missingDragonTicks >= 100) cancel("The dragon disappeared, so the game was stopped.");
        } else missingDragonTicks = 0;
    }

    private void runnersWin() {
        if (winner != null || state != State.RUNNING) return;
        winner = Team.RUNNER;
        markWinners();
        broadcast(Msg.text("The dragon is dead!", DialogPalette.ACCENT));
        // Let the death animation play before everyone heads back.
        tasks.later(200, this::finish);
    }

    private void markWinners() {
        for (Participant participant : roster()) {
            boolean won = team(participant.id) == winner;
            participant.score = won ? 1 : 0;
            participant.place = won ? 1 : 2;
        }
        save();
    }

    @Override protected void checkEnd() {
        if (state != State.RUNNING || winner != null) return;
        if (alive(Team.RUNNER) == 0) {
            winner = Team.HUNTER;
            markWinners();
            finish();
        } else if (playing().isEmpty()) finish();
    }

    @Override protected void cleanup() {
        collisions.forEach((id, value) -> { Player player = Bukkit.getPlayer(id); if (player != null) player.setCollidable(value); });
        collisions.clear();
    }

    @Override protected void playerReturned(Player player, Participant participant) {
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(spawns.getOrDefault(player.getUniqueId(), spectatorSpawn()));
    }

    @Override protected void announceResults() {
        var theme = plugin.getThemes().current();
        if (winner == null) { super.announceResults(); return; }
        Component title = Component.text(winner.many + " win", winner == Team.RUNNER ? theme.primary() : theme.secondary());
        Msg.broadcast(Msg.text(name + " is over. ", DialogPalette.ACCENT).append(title));
        Msg.broadcast(Msg.text("  " + winner.many + ": " + names(winner), DialogPalette.TEXT));
        for (Player player : Bukkit.getOnlinePlayers()) {
            Msg.title(player, title, Msg.text(names(winner), DialogPalette.MUTED), 8, 70, 16);
            Sounds.VICTORY.play(player);
        }
        for (Participant participant : roster()) {
            Player player = Bukkit.getPlayer(participant.id);
            if (player != null && team(participant.id) == winner && plugin.getRegistry().owner(participant.id) == this) {
                plugin.getCelebrations().fireworks(player.getLocation(), 2);
            }
        }
    }

    // ---- Combat --------------------------------------------------------------------------------

    private boolean ours(Player player) { return plugin.getRegistry().owner(player.getUniqueId()) == this; }

    private boolean active(Player player) {
        return state == State.RUNNING && winner == null && ours(player) && isPlaying(player.getUniqueId()) && player.getGameMode() != GameMode.SPECTATOR;
    }

    private static Player attacker(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) return player;
        if (entity instanceof Tameable tame && tame.getOwner() instanceof Player player) return player;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!end().contains(event.getEntity().getLocation())) return;
        Player source = event instanceof EntityDamageByEntityEvent hit ? attacker(hit.getDamager()) : null;
        if (source != null && ours(source) && !active(source)) { event.setCancelled(true); return; }
        if (!(event.getEntity() instanceof Player victim) || !ours(victim)) return;
        if (!active(victim)) {
            event.setCancelled(true);
            if (event.getCause() == EntityDamageEvent.DamageCause.VOID) tasks.later(1, () -> victim.teleport(spectatorSpawn()));
            return;
        }
        if (source != null && !source.equals(victim) && ours(source) && team(source.getUniqueId()) == team(victim.getUniqueId())) { event.setCancelled(true); return; }
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) { event.setCancelled(true); out(victim); return; }
        if (kit != null && (source != null ? kit.isPvpHurt() : kit.isPveHurt())) { HurtRules.removeHealthDamage(event); return; }
        if (victim.getHealth() + victim.getAbsorptionAmount() - event.getFinalDamage() > 0) return;
        if (victim.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || victim.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) return;
        event.setCancelled(true);
        out(victim);
    }

    private void out(Player player) {
        Team team = team(player.getUniqueId());
        long left = Math.max(0, alive(team) - 1);
        var theme = plugin.getThemes().current();
        String remaining = left == 0 ? "No " + team.many.toLowerCase(Locale.ROOT) + " left." : left + " " + (left == 1 ? team.one : team.many).toLowerCase(Locale.ROOT) + " left.";
        eliminate(player, Component.text(player.getName(), team == Team.RUNNER ? theme.primary() : theme.secondary())
                .append(Msg.text(" is out. " + remaining, DialogPalette.MUTED)));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!ours(player)) return;
        event.deathMessage(null);
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (active(player)) out(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!ours(player)) return;
        event.setRespawnLocation(spawns.getOrDefault(player.getUniqueId(), spectatorSpawn()));
        tasks.later(1, () -> { if (player.isOnline() && ours(player)) player.setGameMode(GameMode.SPECTATOR); });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragonDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof EnderDragon && end().contains(event.getEntity().getLocation())) runnersWin();
    }

    // ---- Countdown freeze and world rules ------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (state != State.COUNTDOWN || event instanceof PlayerTeleportEvent || !event.hasChangedPosition() || !ours(event.getPlayer())) return;
        Location held = event.getFrom().clone();
        held.setYaw(event.getTo().getYaw());
        held.setPitch(event.getTo().getPitch());
        event.setTo(held);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!ours(event.getPlayer())) return;
        if (state == State.COUNTDOWN && event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) { event.setCancelled(true); return; }
        // Pearls, chorus fruit and gateways stay inside the End; nobody leaves through a portal.
        if (event.getTo() != null && !end().contains(event.getTo()) && event.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPortal(PlayerPortalEvent event) { if (ours(event.getPlayer())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (ours(event.getPlayer()) && (!active(event.getPlayer()) || kit != null && !kit.isAllowBlockBreak())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (ours(event.getPlayer()) && (!active(event.getPlayer()) || kit != null && !kit.isAllowBlockPlace())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (ours(event.getPlayer()) && (!active(event.getPlayer()) || kit != null && !kit.isAllowBlockPlace())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (!ours(event.getPlayer()) || state == State.RUNNING && active(event.getPlayer())) return;
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) { if (ours(event.getPlayer()) && !active(event.getPlayer())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player && ours(player) && !active(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) { if (ours(event.getPlayer()) && !active(event.getPlayer())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && ours(player) && !active(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventory(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && ours(player) && state == State.COUNTDOWN) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && ours(player) && (!active(player) || kit != null && kit.keepsHungerFull())) {
            event.setCancelled(true);
            player.setFoodLevel(20);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player) || !ours(player) || kit == null || kit.isNaturalRegen()) return;
        if (event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED || event.getRegainReason() == EntityRegainHealthEvent.RegainReason.REGEN) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) { if (ours(event.getPlayer())) event.message(null); }

    // ---- Spectating and results ----------------------------------------------------------------

    @Override public Location spectatorSpawn() {
        World world = end().world();
        if (world == null || !world.getName().equals(worldName)) return null;
        return new Location(world, 0.5, 100, 0.5);
    }

    @Override public boolean contains(Location location) {
        return location != null && location.getWorld() != null && location.getWorld().getName().equals(worldName);
    }

    @Override public String statusText() {
        if (winner != null) return winner.many + " Win";
        return super.statusText();
    }

    @Override protected List<StandingsLayout.Row> standings() {
        List<Participant> order = new ArrayList<>(roster().stream().filter(p -> p.status != Status.WITHDRAWN).toList());
        order.sort(Comparator.comparingInt((Participant p) -> winner != null ? (team(p.id) == winner ? 0 : 1) : team(p.id).ordinal())
                .thenComparingInt(p -> p.status == Status.OUT ? 1 : 0)
                .thenComparing(p -> p.name, String.CASE_INSENSITIVE_ORDER));
        List<StandingsLayout.Row> rows = new ArrayList<>();
        for (Participant p : order) {
            String value = team(p.id).one + (p.status == Status.OUT ? " · Out" : "");
            rows.add(new StandingsLayout.Row(p.id, p.name, value, winner != null && team(p.id) == winner, p.status == Status.OUT));
        }
        return rows;
    }

    @Override protected void writeExtra(ConfigurationSection section) {
        section.set("world", worldName);
        section.set("kit", kit == null ? "" : kit.getId());
        teams.forEach((id, team) -> section.set("teams." + id, team.name()));
        section.set("winner", winner == null ? null : winner.name());
    }
}
