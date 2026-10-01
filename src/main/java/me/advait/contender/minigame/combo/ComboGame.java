package me.advait.contender.minigame.combo;

import io.papermc.paper.registry.data.dialog.input.DialogInput;
import me.advait.contender.Contender;
import me.advait.contender.core.Msg;
import me.advait.contender.core.Sounds;
import me.advait.contender.dialog.*;
import me.advait.contender.duel.HurtRules;
import me.advait.contender.kit.Kit;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.minigame.ArenaGame;
import me.advait.contender.minigame.Minigame;
import me.advait.contender.minigame.MinigameType;
import me.advait.contender.tab.StandingsLayout;
import me.advait.contender.util.Tags;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Combo: players take turns against a practice bot. Your score is how many hits you land before the bot
 * hits you back. A hit back early in the combo (within the retry allowance) restarts the attempt.
 * Knock the bot into the void for an unbeatable score.
 */
public final class ComboGame extends ArenaGame {
    public static final class Type implements MinigameType {
        private final Contender plugin;
        public Type(Contender plugin) { this.plugin = plugin; }
        @Override public String id() { return "combo"; }
        @Override public String name() { return "Combo"; }
        @Override public String description() { return "Take turns comboing a practice bot. The longest combo wins."; }
        @Override public DialogIcon icon() { return DialogIcon.TARGET; }

        @Override public void openCreate(Player director) {
            DialogInput difficulty = DialogInput.singleOption("difficulty", DialogIcon.DUEL.label("Bot Difficulty"), Arrays.stream(ComboDifficulty.values())
                    .map(d -> Dialogs.option(d.name(), d.label(), d == ComboDifficulty.NORMAL)).toList()).width(300).build();
            new GameForm(plugin).open(director, new GameForm.Spec("Combo", "Combo",
                    "One turn each against a bot. A hit back within the retry allowance restarts your try.", true, true, false,
                    List.of(difficulty, GameForm.number("retries", DialogIcon.REFRESH, "Retry Allowance", 0, 20, 5, 1, "%s: up to %s hits")), 1),
                    (p, result) -> {
                        Minigame game = create(result.name(), result.map(), result.kit(), result.roster(), result.values());
                        plugin.getMinigames().select(game);
                        new MinigameDialogs(plugin).control(p, game);
                    }, p -> new TournamentDialogs(plugin).formats(p));
        }

        @Override public Minigame create(String name, ArenaMap map, Kit kit, Map<UUID, String> roster, Map<String, String> options) {
            if (map == null || !map.isComplete()) throw new IllegalArgumentException("Choose a map with both spawns set.");
            requireSword(kit);
            ComboDifficulty difficulty;
            try { difficulty = ComboDifficulty.valueOf(options.getOrDefault("difficulty", "NORMAL")); }
            catch (IllegalArgumentException invalid) { difficulty = ComboDifficulty.NORMAL; }
            return new ComboGame(plugin, this, UUID.randomUUID(), name, roster, map, kit, difficulty, GameForm.intOption(options, "retries", 0, 20, 5));
        }

        @Override public Minigame restore() {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(Minigame.file(plugin, id()));
            if (!yaml.contains("id")) return null;
            var extra = yaml.getConfigurationSection("extra");
            if (extra == null) return null;
            ArenaMap map = plugin.getMapManager().getMap(extra.getString("map", ""));
            Kit kit = plugin.getKitManager().getKit(extra.getString("kit", ""));
            ComboDifficulty difficulty;
            try { difficulty = ComboDifficulty.valueOf(extra.getString("difficulty", "NORMAL")); }
            catch (IllegalArgumentException invalid) { difficulty = ComboDifficulty.NORMAL; }
            ComboGame game = new ComboGame(plugin, this, UUID.fromString(yaml.getString("id")), yaml.getString("name", name()), savedRoster(yaml),
                    map, kit, difficulty, extra.getInt("retries", 5));
            game.readSaved(yaml);
            for (String id : extra.getStringList("cleared")) game.cleared.add(UUID.fromString(id));
            return game;
        }

        private static void requireSword(Kit kit) {
            if (kit == null) throw new IllegalArgumentException("Choose a kit with a sword.");
            if (Arrays.stream(kit.getContents()).noneMatch(ComboGame::sword)) throw new IllegalArgumentException(kit.getDisplayName() + " has no sword. Combo needs one.");
        }
    }

    private static final int BOT_DAMAGE = 7;
    private final ComboDifficulty difficulty;
    private final int retries;
    private final ComboAttackTiming attacks;
    private final Set<UUID> cleared = new HashSet<>();
    private Mannequin bot;
    private org.bukkit.entity.TextDisplay counter;
    private MannequinMotion motion;
    private UUID fighter;
    private int tick, nextTurn, lastAcceptedHit = -1, hits, waitingSince = -1;
    private boolean live, resetting;

    ComboGame(Contender plugin, MinigameType type, UUID id, String name, Map<UUID, String> players, ArenaMap map, Kit kit, ComboDifficulty difficulty, int retries) {
        super(plugin, type, id, name, players, map, kit);
        this.difficulty = difficulty;
        this.retries = retries;
        this.attacks = new ComboAttackTiming(difficulty);
    }

    private static boolean sword(ItemStack item) { return item != null && item.getType().name().endsWith("_SWORD"); }

    @Override protected int countdownSeconds() { return 5; }

    @Override protected boolean fighting(Player player) {
        return state == State.RUNNING && player.getUniqueId().equals(fighter) && !resetting && player.getGameMode() != GameMode.SPECTATOR;
    }

    @Override protected void setup(List<Player> players) {
        List<UUID> order = new ArrayList<>(roster.keySet());
        Collections.shuffle(order);
        // Turn order follows the roster's iteration order, so rebuild it shuffled.
        Map<UUID, Participant> shuffled = new LinkedHashMap<>();
        for (UUID id : order) shuffled.put(id, roster.get(id));
        roster.clear();
        roster.putAll(shuffled);
        for (Player player : players) {
            bench(player);
            player.teleport(spectatorSpawn());
        }
        for (Participant participant : roster.values()) participant.value = "Waiting";
        broadcast(Msg.text("Turn order: " + String.join(", ", roster.values().stream().filter(p -> p.status == Status.PLAYING).map(p -> p.name).toList()), DialogPalette.MUTED));
        broadcast(Msg.text("Land as many hits as you can before the bot hits back." + (retries > 0 ? " A hit back within your first " + retries + " restarts the try." : ""), DialogPalette.MUTED));
    }

    @Override protected void begin() {
        nextTurn = 20;
        tasks.repeat(1, 1, this::step);
    }

    private boolean pending(Participant participant) { return participant.status == Status.PLAYING; }

    private void step() {
        tick++;
        if (tick % 5 == 0) {
            Component bar = fighter == null ? Msg.text("Next turn starting…", DialogPalette.MUTED)
                    : Msg.text(participant(fighter).name + "  ", DialogPalette.TEXT).append(Msg.text("Combo " + hits, DialogPalette.ACCENT));
            Msg.actionBar(audience(), bar);
        }
        if (fighter == null) { nextTurn(); return; }
        Player player = Bukkit.getPlayer(fighter);
        if (player == null) return;
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setFireTicks(0);
        if (bot == null || !bot.isValid() || bot.isDead()) { if (!resetting) retry(); return; }
        bot.setFireTicks(0);
        bot.setHealth(bot.getAttribute(Attribute.MAX_HEALTH).getValue());
        if (live && !resetting && bot.getLocation().getY() < arena.layout().getBounds().minY() - 3) { endTurn(true); return; }
        if (resetting || !live) { motion.drive(bot, player, difficulty, false); return; }
        if (!contains(bot.getLocation())) { retry(); return; }
        double distance = player.getLocation().distanceSquared(bot.getLocation());
        boolean reacting = attacks.reacting(tick);
        motion.drive(bot, player, difficulty, reacting && distance > 1.4);
        if (attacks.shouldSwing(tick, withinReach(player) && bot.hasLineOfSight(player))) {
            bot.swingMainHand();
            // Mannequins can't attack on their own; a sourced hit runs vanilla shields and knockback.
            player.damage(BOT_DAMAGE, bot);
        }
    }

    private boolean withinReach(Player player) {
        var eye = bot.getEyeLocation();
        return player.getBoundingBox().rayTrace(eye.toVector(), eye.getDirection(), difficulty.reach()) != null;
    }

    private void nextTurn() {
        if (tick < nextTurn || state != State.RUNNING) return;
        Participant next = null;
        boolean waiting = false;
        for (Participant participant : roster.values()) {
            if (!pending(participant)) continue;
            Player player = Bukkit.getPlayer(participant.id);
            if (player == null || player.isDead()) { waiting = true; continue; }
            next = participant;
            break;
        }
        if (next == null) {
            if (!waiting) { finish(); return; }
            // Someone who still has a turn is offline. Give them a minute to come back.
            if (waitingSince < 0) {
                waitingSince = tick;
                broadcast(Msg.text("Waiting up to a minute for players who still have a turn.", DialogPalette.MUTED));
            }
            if (tick - waitingSince > 1200) finish();
            return;
        }
        waitingSince = -1;
        fighter = next.id;
        Player player = Bukkit.getPlayer(fighter);
        var theme = plugin.getThemes().current();
        Msg.title(audience(), Component.text(next.name, theme.primary()), Msg.text("is up", DialogPalette.MUTED), 4, 30, 6);
        Sounds.GO.play(audience());
        next.value = "Playing";
        resetPositions(player);
        plugin.getStages().refreshDisplays();
    }

    private void resetPositions(Player player) {
        removeBot();
        live = false;
        resetting = false;
        hits = 0;
        lastAcceptedHit = -1;
        attacks.reset();
        participant(player.getUniqueId()).value = "Playing";
        if (player.getGameMode() == GameMode.SPECTATOR) player.setSpectatorTarget(null);
        deploy(player, arena.layout().getTeam1Spawn());
        protect(player, 0);
        player.setNoDamageTicks(0);
        Location spawn = arena.layout().getTeam2Spawn();
        bot = spawn.getWorld().spawn(spawn, Mannequin.class, entity -> {
            Tags.managed(entity, "combo");
            entity.setRemoveWhenFarAway(false);
            entity.setImmovable(false);
            entity.setDescription(null);
            entity.customName(Component.text("Combo Bot"));
            entity.setCustomNameVisible(false);
            entity.setGravity(true);
            entity.setInvulnerable(false);
            entity.setCollidable(false);
            ComboAppearance.apply(entity);
            Objects.requireNonNull(entity.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(1024);
            entity.setHealth(1024);
            entity.getEquipment().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
            entity.getEquipment().setArmorContents(Arrays.stream(kit.getArmor()).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new));
            var resistance = entity.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            if (resistance != null) resistance.setBaseValue(0);
        });
        motion = new MannequinMotion(bot);
        showCounter(player.getName(), "0", false);
    }

    // ---- The floating counter ------------------------------------------------------------------

    /** A big number over the arena that pops with every hit. */
    private void showCounter(String fighter, String value, boolean pop) {
        var theme = plugin.getThemes().current();
        Component text = Component.text(fighter, theme.secondary()).appendNewline().append(Component.text(value, theme.primary()));
        if (counter == null || !counter.isValid()) {
            Location at = middle().add(0, 3.4, 0);
            counter = me.advait.contender.display.Holograms.text(at, text, 0.01f, org.bukkit.entity.Display.Billboard.CENTER, theme.background(0), "combo");
            org.bukkit.entity.TextDisplay created = counter;
            tasks.later(2, () -> me.advait.contender.display.Holograms.animate(created, me.advait.contender.display.Holograms.scaled(2.4f), 6));
            return;
        }
        counter.text(text);
        if (!pop) return;
        org.bukkit.entity.TextDisplay shown = counter;
        me.advait.contender.display.Holograms.animate(shown, me.advait.contender.display.Holograms.scaled(3.1f), 2);
        tasks.later(3, () -> me.advait.contender.display.Holograms.animate(shown, me.advait.contender.display.Holograms.scaled(2.4f), 4));
    }

    private void hideCounter() {
        me.advait.contender.display.Holograms.remove(counter);
        counter = null;
    }

    private void removeBot() {
        Mannequin old = bot;
        bot = null;
        motion = null;
        if (old != null) old.remove();
    }

    private void retry() {
        if (resetting || fighter == null) return;
        resetting = true;
        hits = 0;
        UUID expected = fighter;
        tasks.later(1, () -> {
            if (!expected.equals(fighter)) return;
            Player player = Bukkit.getPlayer(expected);
            if (player != null) resetPositions(player);
        });
    }

    /** The bot hit back or the player fell. */
    private void hitBack() {
        // Includes falling off before the first hit: start the try again.
        if (hits == 0) { retry(); return; }
        if (hits <= retries) {
            Player player = Bukkit.getPlayer(fighter);
            if (player != null) player.sendActionBar(Msg.text("Hit back at " + hits + ". Try again.", DialogPalette.WARNING));
            retry();
            return;
        }
        endTurn(false);
    }

    private void endTurn(boolean botCleared) {
        if (resetting || fighter == null) return;
        resetting = true;
        UUID id = fighter;
        Participant participant = participant(id);
        participant.status = Status.DONE;
        participant.score = botCleared ? 1_000_000 + hits : hits;
        participant.value = botCleared ? "∞" : hits + (hits == 1 ? " hit" : " hits");
        showCounter(participant.name, botCleared ? "∞" : hits + (hits == 1 ? " hit" : " hits"), true);
        if (botCleared) cleared.add(id);
        var theme = plugin.getThemes().current();
        broadcast(Component.text(participant.name, theme.primary()).append(Msg.text(botCleared ? " knocked the bot into the void!" : " scored a " + hits + "-hit combo.", DialogPalette.TEXT)));
        Player player = Bukkit.getPlayer(id);
        if (player != null) {
            Msg.title(player, Component.text(botCleared ? "∞" : String.valueOf(hits), theme.primary()), Msg.text(botCleared ? "Into the void" : "hit combo", DialogPalette.MUTED), 2, 40, 8);
            (botCleared ? Sounds.VICTORY : Sounds.ROUND_WIN).play(player);
        }
        save();
        tasks.later(1, () -> {
            if (!id.equals(fighter)) return;
            fighter = null;
            removeBot();
            live = false;
            resetting = false;
            Player online = Bukkit.getPlayer(id);
            if (online != null) {
                bench(online);
                online.teleport(spectatorSpawn());
            }
            nextTurn = tick + 50;
            plugin.getStages().refreshDisplays();
        });
    }

    @Override protected void killed(Player victim, Player killer) {
        if (victim.getUniqueId().equals(fighter) && !resetting) hitBack();
    }

    @Override protected void playerLeft(Participant participant) {
        if (participant.id.equals(fighter)) {
            if (hits > retries) endTurn(false);
            else {
                // Too early to count; they get their turn when they come back.
                fighter = null;
                removeBot();
                live = false;
                resetting = false;
                participant.value = "Waiting";
                nextTurn = tick + 20;
            }
        }
    }

    @Override protected void checkEnd() { }

    @Override public void withdraw(UUID player) {
        if (player.equals(fighter)) {
            fighter = null;
            removeBot();
            live = false;
            resetting = false;
            nextTurn = tick + 20;
        }
        super.withdraw(player);
    }

    @Override protected void cleanup() {
        hideCounter();
        removeBot();
        fighter = null;
        super.cleanup();
    }

    // ---- Bot hits ------------------------------------------------------------------------------

    private boolean isBot(Entity entity) { return bot != null && bot.getUniqueId().equals(entity.getUniqueId()); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBotDamage(EntityDamageEvent event) {
        if (isBot(event.getEntity())) {
            if (!(event instanceof EntityDamageByEntityEvent hit) || !(hit.getDamager() instanceof Player player) || !fighting(player)
                    || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK || !sword(player.getInventory().getItemInMainHand())) {
                event.setCancelled(true);
                return;
            }
            HurtRules.removeHealthDamage(event);
        } else if (event instanceof EntityDamageByEntityEvent hit && isBot(hit.getDamager()) && event.getEntity() instanceof Player player) {
            if (!fighting(player) || !live) { event.setCancelled(true); return; }
            // The bot's hits push you around but never hurt.
            HurtRules.removeHealthDamage(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getDamage() <= 0 || resetting) return;
        if (isBot(event.getEntity()) && event.getDamager() instanceof Player player && fighting(player) && lastAcceptedHit != tick) {
            lastAcceptedHit = tick;
            hits++;
            attacks.hit(tick);
            live = true;
            ComboAppearance.hurt(bot);
            showCounter(player.getName(), Integer.toString(hits), true);
            participant(player.getUniqueId()).value = "Combo " + hits;
        } else if (event.getEntity() instanceof Player player && isBot(event.getDamager()) && fighting(player) && live) {
            // A fully blocked swing doesn't break the combo.
            if (event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)
                    && event.getDamage() + event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) <= 0) return;
            hitBack();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void onBotDeath(EntityDeathEvent event) {
        if (!isBot(event.getEntity())) return;
        event.setCancelled(true);
        event.getDrops().clear();
    }

    @EventHandler public void onBotInteract(PlayerInteractEntityEvent event) { if (isBot(event.getRightClicked())) event.setCancelled(true); }
    @EventHandler public void onBotTeleport(EntityTeleportEvent event) { if (isBot(event.getEntity())) event.setCancelled(true); }
    @EventHandler public void onBotPortal(EntityPortalEvent event) { if (isBot(event.getEntity())) event.setCancelled(true); }
    @EventHandler public void onBotPickup(EntityPickupItemEvent event) { if (isBot(event.getEntity())) event.setCancelled(true); }
    @EventHandler public void onFish(PlayerFishEvent event) { if (event.getCaught() != null && isBot(event.getCaught())) event.setCancelled(true); }

    // ---- Results -------------------------------------------------------------------------------

    @Override protected List<StandingsLayout.Row> standings() {
        List<Participant> order = new ArrayList<>(roster().stream().filter(p -> p.status != Status.WITHDRAWN || p.score > 0).toList());
        order.sort(Comparator.comparingInt((Participant p) -> p.status == Status.DONE ? 0 : pending(p) ? 1 : 2)
                .thenComparing(Comparator.comparingDouble((Participant p) -> p.score).reversed()));
        if (finished()) order.sort(Comparator.comparingDouble((Participant p) -> p.status == Status.DONE ? p.score : -1).reversed());
        List<StandingsLayout.Row> rows = new ArrayList<>();
        for (Participant p : order) {
            String value = p.status == Status.DONE ? p.value : p.id.equals(fighter) ? "Combo " + hits
                    : finished() ? "No turn" : state == State.READY ? "Waiting" : p.value.isBlank() ? "Waiting" : p.value;
            rows.add(new StandingsLayout.Row(p.id, p.name, value, p.id.equals(fighter) || cleared.contains(p.id), p.status != Status.DONE && finished()));
        }
        return rows;
    }

    @Override protected void writeExtra(ConfigurationSection section) {
        super.writeExtra(section);
        section.set("difficulty", difficulty.name());
        section.set("retries", retries);
        section.set("cleared", cleared.stream().map(UUID::toString).toList());
    }
}
