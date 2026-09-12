package me.advait.contender.race;

import me.advait.contender.arena.*;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.testutil.StateTestServer;
import io.papermc.paper.registry.RegistryAccess;
import org.bukkit.*;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class RaceSessionTest {
    @Test void racersCanArrangeTheirInventoryDuringCountdownAndTheRace() {
        try (var f = new TargetFixture()) {
            var view = inventoryView(f);
            for (boolean started : List.of(false, true)) {
                if (started) for (int i = 0; i < 10; i++) f.env.scheduled.getFirst().run();
                for (InventoryAction action : List.of(InventoryAction.PICKUP_ALL, InventoryAction.PLACE_ALL,
                        InventoryAction.SWAP_WITH_CURSOR, InventoryAction.HOTBAR_SWAP,
                        InventoryAction.MOVE_TO_OTHER_INVENTORY, InventoryAction.COLLECT_TO_CURSOR)) {
                    var event = new InventoryClickEvent(view, InventoryType.SlotType.QUICKBAR, 36,
                            action == InventoryAction.HOTBAR_SWAP ? ClickType.NUMBER_KEY : ClickType.LEFT, action, 1);
                    f.session.inventory(event); assertFalse(event.isCancelled(), action.name());
                }
                var drag = new InventoryDragEvent(view, null, mock(ItemStack.class), false,
                        Map.of(36, mock(ItemStack.class), 37, mock(ItemStack.class)));
                f.session.inventory(drag); assertFalse(drag.isCancelled());
                var swap = new PlayerSwapHandItemsEvent(f.player, null, null);
                f.session.swap(swap); assertFalse(swap.isCancelled());
            }
        }
    }
    @Test void inventoryReorderingCannotMoveKitItemsIntoCraftingOrContainersOrDropThem() {
        try (var f = new TargetFixture()) {
            var view = inventoryView(f);
            for (int rawSlot : List.of(-999, 0, 1, 4)) {
                var event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot,
                        ClickType.LEFT, InventoryAction.PLACE_ALL);
                f.session.inventory(event); assertTrue(event.isCancelled());
            }
            for (InventoryAction action : List.of(InventoryAction.DROP_ALL_CURSOR, InventoryAction.DROP_ONE_CURSOR,
                    InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT, InventoryAction.CLONE_STACK, InventoryAction.UNKNOWN)) {
                var event = new InventoryClickEvent(view, InventoryType.SlotType.QUICKBAR, 36, ClickType.LEFT, action);
                f.session.inventory(event); assertTrue(event.isCancelled(), action.name());
            }
            var mixedDrag = new InventoryDragEvent(view, null, mock(ItemStack.class), false,
                    Map.of(36, mock(ItemStack.class), 1, mock(ItemStack.class)));
            f.session.inventory(mixedDrag); assertTrue(mixedDrag.isCancelled());
            when(view.getType()).thenReturn(InventoryType.CHEST);
            for (InventoryAction action : List.of(InventoryAction.MOVE_TO_OTHER_INVENTORY, InventoryAction.COLLECT_TO_CURSOR,
                    InventoryAction.HOTBAR_SWAP, InventoryAction.PICKUP_ALL)) {
                var event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 36, ClickType.LEFT, action);
                f.session.inventory(event); assertTrue(event.isCancelled(), action.name());
            }
            var drag = new InventoryDragEvent(view, null, mock(ItemStack.class), false, Map.of(36, mock(ItemStack.class)));
            f.session.inventory(drag); assertTrue(drag.isCancelled());
        }
    }
    @Test void finishersCannotRearrangeAndExistingCancellationsAreRespected() {
        try (var f = new TargetFixture()) {
            var view = inventoryView(f);
            var alreadyCancelled = new InventoryClickEvent(view, InventoryType.SlotType.QUICKBAR, 36, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            alreadyCancelled.setCancelled(true); f.session.inventory(alreadyCancelled); assertTrue(alreadyCancelled.isCancelled());
            for (int i = 0; i < 10; i++) f.env.scheduled.getFirst().run();
            f.run.hit(f.player.getUniqueId(), 2, System.nanoTime());
            var event = new InventoryClickEvent(view, InventoryType.SlotType.QUICKBAR, 36, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            f.session.inventory(event); assertTrue(event.isCancelled());
            var drag = new InventoryDragEvent(view, null, mock(ItemStack.class), false, Map.of(36, mock(ItemStack.class)));
            f.session.inventory(drag); assertTrue(drag.isCancelled());
            var swap = new PlayerSwapHandItemsEvent(f.player, null, null);
            f.session.swap(swap); assertTrue(swap.isCancelled());
            Player outsider = mock(Player.class); when(outsider.getUniqueId()).thenReturn(UUID.randomUUID());
            when(view.getPlayer()).thenReturn(outsider);
            var outside = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PLACE_ALL);
            f.session.inventory(outside); assertFalse(outside.isCancelled());
        }
    }
    @Test void bedWorksFromEitherHandAndUsesTheReturnHeightBeforeAnyCheckpoint() {
        try (var f = new TargetFixture()) {
            f.kit.when(() -> RaceKit.isReturn(any())).thenReturn(true);
            for (int i = 0; i < 10; i++) f.env.scheduled.getFirst().run();
            clearInvocations(f.player);
            var bed = new PlayerInteractEvent(f.player, org.bukkit.event.block.Action.RIGHT_CLICK_AIR,
                    mock(ItemStack.class), null, null, EquipmentSlot.OFF_HAND);
            f.session.bed(bed); assertTrue(bed.isCancelled());
            verify(f.player).teleport(argThat((Location loc) -> loc.getX() == 1 && loc.getY() == 67));
            f.session.bed(new PlayerInteractEvent(f.player, org.bukkit.event.block.Action.RIGHT_CLICK_AIR,
                    mock(ItemStack.class), null, null, EquipmentSlot.HAND));
            verify(f.player, times(1)).teleport(any(Location.class));
            verify(f.player).setVelocity(new Vector()); verify(f.player).setFallDistance(0);
        }
    }
    private InventoryView inventoryView(TargetFixture f) {
        var menus = Registry.MENU;
        doAnswer(call -> mock(org.bukkit.inventory.MenuType.Typed.class)).when(menus).getOrThrow(any(net.kyori.adventure.key.Key.class));
        var view = mock(InventoryView.class);
        var crafting = mock(Inventory.class);
        var type = InventoryType.CRAFTING;
        when(view.getPlayer()).thenReturn(f.player); when(view.getType()).thenReturn(type);
        when(view.getInventory(anyInt())).thenAnswer(call -> {
            int slot = call.getArgument(0);
            return slot < 0 ? null : slot < 5 ? crafting : f.player.getInventory();
        });
        return view;
    }
    @Test void checkpointMobsStayFixedWhileRacerKnockbackAndUnrelatedMobsStillWork() {
        try (var f = new TargetFixture()) {
            var moved = new io.papermc.paper.event.entity.EntityMoveEvent(f.target, f.target.getLocation(), f.target.getLocation().add(3, -1, 2));
            f.session.mobMove(moved); assertTrue(moved.isCancelled());
            var pushed = new io.papermc.paper.event.entity.EntityKnockbackEvent(f.target,
                    io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.EXPLOSION, new Vector(1, 1, 1));
            f.session.mobKnockback(pushed); assertTrue(pushed.isCancelled());
            var racerPushed = new io.papermc.paper.event.entity.EntityKnockbackEvent(f.player,
                    io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.ENTITY_ATTACK, new Vector(1, 1, 1));
            f.session.mobKnockback(racerPushed); assertFalse(racerPushed.isCancelled());
            var outsider = mob("Other", f.world, 8);
            var otherMove = new io.papermc.paper.event.entity.EntityMoveEvent(outsider, outsider.getLocation(), outsider.getLocation().add(1, 0, 0));
            f.session.mobMove(otherMove); assertFalse(otherMove.isCancelled());
            var riding = new EntityMountEvent(f.target, mock(Boat.class));
            f.session.mobMount(riding); assertTrue(riding.isCancelled());
            var mountTarget = new EntityMountEvent(f.player, f.target);
            f.session.mobMount(mountTarget); assertTrue(mountTarget.isCancelled());
            var leash = new PlayerLeashEntityEvent(f.target, f.player, f.player, EquipmentSlot.HAND);
            f.session.mobLeash(leash); assertTrue(leash.isCancelled());
            var interact = new PlayerInteractEntityEvent(f.player, f.target, EquipmentSlot.HAND);
            f.session.mobInteract(interact); assertTrue(interact.isCancelled());
            var teleport = new EntityTeleportEvent(f.target, f.target.getLocation(), f.target.getLocation().add(1, 0, 0));
            f.session.mobTeleport(teleport); assertTrue(teleport.isCancelled());
        }
    }

    @Test void checkpointAnchorIsRestoredEveryTickAndInternalTeleportIsAllowed() {
        try (var f = new TargetFixture()) {
            Location anchor = f.target.getLocation();
            when(f.target.getLocation()).thenAnswer(call -> anchor.clone().add(2, 0, 0));
            doAnswer(call -> {
                var event = new EntityTeleportEvent(f.target, f.target.getLocation(), call.getArgument(0));
                f.session.mobTeleport(event); assertFalse(event.isCancelled()); return true;
            }).when(f.target).teleport(any(Location.class));
            f.env.scheduled.getLast().run();
            verify(f.target).teleport(anchor);
            verify(f.target, atLeastOnce()).setVelocity(new Vector());
            verify(f.manager, never()).failed(any());
        }
    }

    @Test void checkpointDamageCannotKillAndValidMaceHitsStillScore() {
        try (var f = new TargetFixture()) {
            var environmental = mock(EntityDamageEvent.class);
            when(environmental.getEntity()).thenReturn(f.target);
            f.session.damage(environmental); verify(environmental).setCancelled(true);
            for (int i = 0; i < 10; i++) f.env.scheduled.getFirst().run();
            var attack = mock(EntityDamageByEntityEvent.class);
            when(attack.getEntity()).thenReturn(f.target); when(attack.getDamager()).thenReturn(f.player);
            when(attack.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);
            when(attack.getDamage()).thenReturn(5000d); when(attack.getFinalDamage()).thenReturn(10d);
            f.kit.when(() -> RaceKit.isWeapon(any())).thenReturn(true);
            f.session.damage(attack); verify(attack).setDamage(10d); verify(attack, never()).setCancelled(true);
            verify(f.target, atLeastOnce()).setHealth(1024d);
            f.session.scored(attack); assertEquals(1, f.run.racer(f.player.getUniqueId()).checkpoint());
            var death = new EntityDeathEvent(f.target, mock(org.bukkit.damage.DamageSource.class), new ArrayList<>(), 25);
            f.session.mobDeath(death); assertTrue(death.isCancelled()); assertEquals(1024d, death.getReviveHealth());
            assertEquals(0, death.getDroppedExp()); assertTrue(death.getDrops().isEmpty()); assertFalse(death.shouldPlayDeathSound());
            var otherDeath = new EntityDeathEvent(mob("Other", f.world, 8), mock(org.bukkit.damage.DamageSource.class), new ArrayList<>());
            f.session.mobDeath(otherDeath); assertFalse(otherDeath.isCancelled());
            var explosion = new ExplosionPrimeEvent(f.target, 3f, false);
            f.session.mobExplode(explosion); assertTrue(explosion.isCancelled());
            verify(f.target).setInvulnerable(false); // A true flag would prevent native Wind Burst from firing.
        }
    }

    @Test void peacefulArenaChangesDifficultyBeforeItsCheckpointChunksLoad() {
        try (var f = new TargetFixture()) {
            var order = inOrder(f.world);
            order.verify(f.world).setDifficulty(Difficulty.NORMAL);
            order.verify(f.world).getChunkAtAsync(0, 0);
        }
    }

    @Test void returnsToLastCheckpointAndRejoinKeepsProgressWhileCleanupInvalidatesCallbacks() {
        try (var env = new StateTestServer(); var access = mockStatic(RegistryAccess.class); var bukkit = mockStatic(Bukkit.class); var kit = mockStatic(RaceKit.class)) {
            access.when(RegistryAccess::registryAccess).thenReturn(mock(RegistryAccess.class, RETURNS_MOCKS));
            var attributes = new HashMap<net.kyori.adventure.key.Key, org.bukkit.attribute.Attribute>();
            var attributeRegistry = Registry.ATTRIBUTE;
            doAnswer(call -> attributes.computeIfAbsent(call.getArgument(0), key -> {
                var attribute = mock(org.bukkit.attribute.Attribute.class);
                when(attribute.getKey()).thenReturn(new NamespacedKey(key.namespace(), key.value())); return attribute;
            })).when(attributeRegistry).getOrThrow(any(net.kyori.adventure.key.Key.class));
            var manager = mock(RaceManager.class); var world = mock(World.class);
            when(world.getName()).thenReturn("arena"); bukkit.when(() -> Bukkit.getWorld("arena")).thenReturn(world);
            var chunk = mock(Chunk.class); when(chunk.addPluginChunkTicket(env.plugin)).thenReturn(true);
            when(world.getChunkAtAsync(0, 0)).thenReturn(CompletableFuture.completedFuture(chunk));
            when(world.getChunkAt(0, 0)).thenReturn(chunk);
            LivingEntity first = mob("#1", world, 2), second = mob("#2", world, 4), finish = mob("Finish", world, 6);
            when(chunk.getEntities()).thenReturn(new Entity[]{first, second, finish});
            ArenaMap map = map(); var arena = mock(ArenaInstance.class); when(arena.map()).thenReturn(map);
            when(arena.cell()).thenReturn(new me.advait.contender.map.BlockBounds(0, -64, 0, 100, 320, 100));
            Player player = mock(Player.class); UUID id = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id);
            when(player.getLocation()).thenAnswer(i -> new Location(world, 1, 64, 1));
            when(player.getBoundingBox()).thenAnswer(i -> new org.bukkit.util.BoundingBox(.7, 64, .7, 1.3, 65.8, 1.3));
            when(player.isOnline()).thenReturn(true); when(player.getMaxHealth()).thenReturn(20d);
            when(player.getActivePotionEffects()).thenReturn(List.of()); when(player.teleport(any(Location.class))).thenReturn(true);
            when(player.getInventory()).thenReturn(mock(PlayerInventory.class)); bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            var run = new RaceRun(UUID.randomUUID(), "Race", "course", 15, 0, List.of(new RaceRun.Racer(id, "Alice")));
            var session = new RaceSession(env.plugin, manager, run, RaceCourse.defaults("course"), new ArenaLease(arena, UUID.randomUUID()));
            session.enable(); assertTrue(session.owns(id)); assertEquals(RaceRun.State.COUNTDOWN, run.state());
            verify(manager).capture(player); kit.verify(() -> RaceKit.give(player));
            verify(first).setMaximumNoDamageTicks(0); verify(first).setInvulnerable(false);
            var countdown = env.scheduled.getFirst();
            for (int i = 0; i < 10; i++) countdown.run(); assertEquals(RaceRun.State.RUNNING, run.state());
            run.hit(id, 2, System.nanoTime());
            Location from = new Location(world, 4, 64, 4), to = new Location(world, 4, 50, 4);
            var fall = new PlayerMoveEvent(player, from, to); session.move(fall);
            assertEquals(70, fall.getTo().getY()); assertEquals(4, fall.getTo().getX());
            var escape = new PlayerTeleportEvent(player, from, new Location(world, 150, 70, 4), PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
            session.teleport(escape); assertTrue(escape.isCancelled());
            var wind = new PlayerMoveEvent(player, from, new Location(world, 50, 150, 4)); session.move(wind);
            assertEquals(150, wind.getTo().getY()); // airborne outside template remains inside this cell
            var hunger = mock(FoodLevelChangeEvent.class); when(hunger.getEntity()).thenReturn(player); session.food(hunger); verify(hunger).setCancelled(true);
            session.join(new PlayerJoinEvent(player, Component.empty())); env.scheduled.getLast().run();
            assertEquals(2, run.racer(id).checkpoint()); kit.verify(() -> RaceKit.give(player), times(1));
            session.disable(); verify(manager).restore(player); verify(chunk).removePluginChunkTicket(env.plugin);
            int tasks = env.scheduled.size(); for (var scheduled : List.copyOf(env.scheduled)) scheduled.run(); assertEquals(tasks, env.scheduled.size());
            verify(manager, times(1)).restore(player);
        }
    }
    @Test void missingOrDuplicateCheckpointsFailBeforePlayersAreChanged() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            World world = mock(World.class); when(world.getName()).thenReturn("arena"); bukkit.when(() -> Bukkit.getWorld("arena")).thenReturn(world);
            Chunk chunk = mock(Chunk.class); when(world.getChunkAt(0, 0)).thenReturn(chunk);
            Entity[] missing = {mob("#1", world, 2), mob("#3", world, 4), mob("Finish", world, 6)};
            when(chunk.getEntities()).thenReturn(missing);
            assertTrue(assertThrows(IllegalArgumentException.class, () -> RaceManager.scan(map(), RaceCourse.defaults("course"))).getMessage().contains("#2"));
            Entity[] duplicates = {mob("#1", world, 2), mob("#1", world, 4), mob("Finish", world, 6)};
            when(chunk.getEntities()).thenReturn(duplicates);
            assertTrue(assertThrows(IllegalArgumentException.class, () -> RaceManager.scan(map(), RaceCourse.defaults("course"))).getMessage().contains("Duplicate"));
        }
    }
    private LivingEntity mob(String name, World world, int x) {
        LivingEntity mob = mock(LivingEntity.class); when(mob.getUniqueId()).thenReturn(UUID.randomUUID()); when(mob.isValid()).thenReturn(true);
        when(mob.customName()).thenReturn(Component.text(name)); when(mob.getLocation()).thenAnswer(i -> new Location(world, x, 64, 4));
        var health = mock(AttributeInstance.class); when(health.getValue()).thenReturn(1024d);
        when(mob.getAttribute(any())).thenReturn(health); when(mob.getMaxHealth()).thenReturn(1024d);
        return mob;
    }
    private ArenaMap map() {
        var map = new ArenaMap("course"); map.setWorldName("arena"); map.setRollbackRegion(0, 60, 0, 10, 80, 10);
        map.setTeam1Spawn(1, 64, 1, 0, 0); map.setTeam2Spawn(1, 64, 1, 0, 0); return map;
    }

    private final class TargetFixture implements AutoCloseable {
        final StateTestServer env = new StateTestServer();
        final org.mockito.MockedStatic<RegistryAccess> access = mockStatic(RegistryAccess.class);
        final org.mockito.MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final org.mockito.MockedStatic<RaceKit> kit = mockStatic(RaceKit.class);
        final RaceManager manager = mock(RaceManager.class);
        final World world = mock(World.class);
        final Player player = mock(Player.class);
        final LivingEntity target;
        final RaceRun run;
        final RaceSession session;

        TargetFixture() {
            access.when(RegistryAccess::registryAccess).thenReturn(mock(RegistryAccess.class, RETURNS_MOCKS));
            var attributes = new HashMap<net.kyori.adventure.key.Key, org.bukkit.attribute.Attribute>();
            var attributeRegistry = Registry.ATTRIBUTE;
            doAnswer(call -> attributes.computeIfAbsent(call.getArgument(0), key -> {
                var attribute = mock(org.bukkit.attribute.Attribute.class);
                when(attribute.getKey()).thenReturn(new NamespacedKey(key.namespace(), key.value())); return attribute;
            })).when(attributeRegistry).getOrThrow(any(net.kyori.adventure.key.Key.class));
            when(world.getName()).thenReturn("arena"); when(world.getDifficulty()).thenReturn(Difficulty.PEACEFUL);
            bukkit.when(() -> Bukkit.getWorld("arena")).thenReturn(world);
            var chunk = mock(Chunk.class); when(chunk.addPluginChunkTicket(env.plugin)).thenReturn(true);
            when(world.getChunkAtAsync(0, 0)).thenReturn(CompletableFuture.completedFuture(chunk));
            when(world.getChunkAt(0, 0)).thenReturn(chunk);
            target = mob("#1", world, 2);
            LivingEntity finish = mob("Finish", world, 6);
            when(chunk.getEntities()).thenReturn(new Entity[]{target, finish});
            ArenaMap map = map();
            var arena = mock(ArenaInstance.class); when(arena.map()).thenReturn(map);
            when(arena.cell()).thenReturn(new me.advait.contender.map.BlockBounds(0, -64, 0, 100, 320, 100));
            UUID id = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id);
            when(player.getLocation()).thenAnswer(call -> new Location(world, 1, 64, 1));
            when(player.isOnline()).thenReturn(true); when(player.getMaxHealth()).thenReturn(20d);
            when(player.getActivePotionEffects()).thenReturn(List.of()); when(player.teleport(any(Location.class))).thenReturn(true);
            when(player.getInventory()).thenReturn(mock(PlayerInventory.class));
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            run = new RaceRun(UUID.randomUUID(), "Race", "course", 15, 0, List.of(new RaceRun.Racer(id, "Alice")));
            session = new RaceSession(env.plugin, manager, run, new RaceCourse("course", 15, "Finish", 3, false), new ArenaLease(arena, UUID.randomUUID()));
            session.enable(); assertEquals(RaceRun.State.COUNTDOWN, run.state());
        }
        @Override public void close() {
            session.disable(); kit.close(); bukkit.close(); access.close(); env.close();
        }
    }
}
