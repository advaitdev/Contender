package me.advait.contender.race;

import me.advait.contender.arena.*;
import me.advait.contender.map.*;
import me.advait.contender.testutil.StateTestServer;
import io.papermc.paper.registry.RegistryAccess;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class RaceSafetyTest {
    @Test void landingReturnsToLatestCheckpointAndTheStartDoesNotLoop() {
        try (var f = new Fixture(true)) {
            f.start(); clearInvocations(f.player);
            f.session.monitorRacers(); verify(f.player, never()).teleport(any(Location.class));
            f.at(2, 67, 2); f.session.monitorRacers();
            f.at(2, 64, 2); f.session.monitorRacers();
            verify(f.player).teleport(argThat((Location loc) -> loc.getX() == 1 && loc.getY() == 64));
            clearInvocations(f.player);
            f.at(1, 64, 1); f.session.monitorRacers(); f.session.monitorRacers();
            verify(f.player, never()).teleport(any(Location.class));
            f.run.hit(f.id, 1, System.nanoTime()); f.at(5, 70, 5); f.session.monitorRacers();
            var landing = new PlayerMoveEvent(f.player, f.player.getLocation(), new Location(f.world, 5, 64, 5));
            f.session.move(landing);
            verify(f.player).teleport(argThat((Location loc) -> loc.getX() == 3 && loc.getY() == 67));
            assertEquals(1, f.run.racer(f.id).checkpoint());
        }
    }
    @Test void disablingGroundReturnStillKeepsFullHungerAndHealthDamageAtZero() {
        try (var f = new Fixture(false)) {
            f.start(); clearInvocations(f.player);
            f.at(4, 70, 4); f.session.monitorRacers(); f.at(4, 64, 4); f.session.monitorRacers();
            verify(f.player, never()).teleport(any(Location.class));
            verify(f.player, times(2)).setFoodLevel(20); verify(f.player, times(2)).setSaturation(20); verify(f.player, times(2)).setExhaustion(0);
            for (var cause : List.of(EntityDamageEvent.DamageCause.FALL, EntityDamageEvent.DamageCause.FIRE_TICK,
                    EntityDamageEvent.DamageCause.LAVA, EntityDamageEvent.DamageCause.DROWNING, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION,
                    EntityDamageEvent.DamageCause.POISON, EntityDamageEvent.DamageCause.STARVATION, EntityDamageEvent.DamageCause.FREEZE)) {
                var event = hit(f.player, cause); f.session.damage(event);
                assertEquals(0, event.getFinalDamage(), cause.name()); assertFalse(event.isCancelled(), cause.name());
                assertEquals(8, event.getDamage()); assertEquals(0, event.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION));
            }
            var food = mock(FoodLevelChangeEvent.class); when(food.getEntity()).thenReturn(f.player); f.session.food(food); verify(food).setCancelled(true);
            var exhaustion = mock(EntityExhaustionEvent.class); when(exhaustion.getEntity()).thenReturn(f.player); f.session.exhaustion(exhaustion); verify(exhaustion).setCancelled(true);
            var attack = mock(EntityDamageByEntityEvent.class); when(attack.getEntity()).thenReturn(f.player); when(attack.getDamager()).thenReturn(mock(Player.class));
            f.session.damage(attack); verify(attack).setCancelled(true);
            var voidHit = hit(f.player, EntityDamageEvent.DamageCause.VOID); f.session.damage(voidHit); assertTrue(voidHit.isCancelled());
            verify(f.player).teleport(argThat((Location loc) -> loc.getX() == 1 && loc.getY() == 64));
        }
    }
    @Test void activeRacersCanHitEachOtherWithoutLosingHealthOrAbsorption() {
        try (var f = new Fixture(false, true)) {
            f.start(); verify(f.world).setPVP(true); clearInvocations(f.player);
            var arrow = mock(Arrow.class); when(arrow.getShooter()).thenReturn(f.opponent);
            for (Entity attacker : List.of(f.opponent, arrow)) {
                var event = hurt(attacker, f.player, attacker instanceof Player
                        ? EntityDamageEvent.DamageCause.ENTITY_ATTACK : EntityDamageEvent.DamageCause.PROJECTILE);
                f.session.damage(event);
                assertFalse(event.isCancelled()); assertEquals(8, event.getDamage());
                assertEquals(0, event.getFinalDamage()); assertEquals(0, event.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION));
            }
            var selfCharge = mock(WindCharge.class); when(selfCharge.getShooter()).thenReturn(f.player);
            var blast = hurt(selfCharge, f.player, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION);
            f.session.damage(blast); assertFalse(blast.isCancelled()); assertEquals(0, blast.getFinalDamage());
            verify(f.player, never()).setVelocity(any());
            var food = mock(EntityExhaustionEvent.class); when(food.getEntity()).thenReturn(f.player);
            f.session.exhaustion(food); verify(food).setCancelled(true);
            assertEquals(0, f.run.racer(f.id).checkpoint()); assertEquals(0, f.run.racer(f.opponentId).checkpoint());
        }
    }
    @Test void countdownFinishersAndOldProjectilesCannotHitRacers() {
        try (var f = new Fixture(false, true)) {
            var beforeStart = hurt(f.opponent, f.player, EntityDamageEvent.DamageCause.ENTITY_ATTACK);
            f.session.damage(beforeStart); assertTrue(beforeStart.isCancelled());
            f.start(); f.run.hit(f.opponentId, 2, System.nanoTime());
            var afterFinish = hurt(f.opponent, f.player, EntityDamageEvent.DamageCause.ENTITY_ATTACK);
            f.session.damage(afterFinish); assertTrue(afterFinish.isCancelled());
            var targetFinisher = hurt(f.player, f.opponent, EntityDamageEvent.DamageCause.ENTITY_ATTACK);
            f.session.damage(targetFinisher); assertTrue(targetFinisher.isCancelled());
            var arrow = mock(Arrow.class); when(arrow.getShooter()).thenReturn(f.opponent);
            var lateArrow = hurt(arrow, f.player, EntityDamageEvent.DamageCause.PROJECTILE);
            f.session.damage(lateArrow); assertTrue(lateArrow.isCancelled());
        }
    }
    @Test void withdrawnPlayersAndOutsidersCannotExchangeHitsWithRacers() {
        try (var f = new Fixture(false, true)) {
            f.start(); f.session.withdraw(f.opponentId);
            var arrow = mock(Arrow.class); when(arrow.getShooter()).thenReturn(f.opponent);
            var delayed = hurt(arrow, f.player, EntityDamageEvent.DamageCause.PROJECTILE);
            f.session.damage(delayed); assertTrue(delayed.isCancelled());
            var outsider = mock(Player.class); when(outsider.getUniqueId()).thenReturn(UUID.randomUUID());
            var incoming = hurt(outsider, f.player, EntityDamageEvent.DamageCause.ENTITY_ATTACK);
            var outgoing = hurt(f.player, outsider, EntityDamageEvent.DamageCause.ENTITY_ATTACK);
            f.session.damage(incoming); f.session.damage(outgoing);
            assertTrue(incoming.isCancelled()); assertTrue(outgoing.isCancelled());
        }
    }
    @Test void countdownAndFinishersDoNotTriggerGroundReturns() {
        try (var f = new Fixture(true)) {
            clearInvocations(f.player);
            f.at(5, 70, 5); f.session.monitorRacers(); f.at(5, 64, 5); f.session.monitorRacers();
            verify(f.player, never()).teleport(any(Location.class));
            f.start(); f.at(5, 70, 5); f.session.monitorRacers();
            f.run.hit(f.id, 2, System.nanoTime()); f.at(5, 64, 5); f.session.monitorRacers();
            verify(f.player, never()).teleport(any(Location.class));
        }
    }
    @SuppressWarnings("deprecation")
    private EntityDamageEvent hit(Player player, EntityDamageEvent.DamageCause cause) { return hurt(null, player, cause); }
    @SuppressWarnings("deprecation")
    private EntityDamageEvent hurt(Entity attacker, Player player, EntityDamageEvent.DamageCause cause) {
        var values = new EnumMap<EntityDamageEvent.DamageModifier, Double>(EntityDamageEvent.DamageModifier.class);
        var functions = new EnumMap<EntityDamageEvent.DamageModifier, com.google.common.base.Function<? super Double, Double>>(EntityDamageEvent.DamageModifier.class);
        for (var modifier : EntityDamageEvent.DamageModifier.values()) { values.put(modifier, 0d); functions.put(modifier, d -> 0d); }
        values.put(EntityDamageEvent.DamageModifier.BASE, 8d); values.put(EntityDamageEvent.DamageModifier.ABSORPTION, -2d);
        return attacker == null ? new EntityDamageEvent(player, cause, mock(DamageSource.class), values, functions)
                : new EntityDamageByEntityEvent(attacker, player, cause, mock(DamageSource.class), values, functions, false);
    }
    @Test void onlyTheFirstAcceptedFinishLaunchesFireworks() {
        try (var f = new Fixture(true)) {
            f.kit.when(() -> RaceKit.isWeapon(any())).thenReturn(true);
            var names = mock(me.advait.contender.nametag.NameTagManager.class);
            when(f.env.plugin.getNameTagManager()).thenReturn(names);
            when(names.displayName(f.id, "Alice")).thenReturn(Component.text("Alice"));
            var finish = attack(f.player, f.mobs[1]);
            f.session.scored(finish); verify(f.manager, never()).celebrate(any());
            f.start(); f.session.scored(attack(f.player, f.mobs[0]));
            verify(f.manager, never()).celebrate(any());
            clearInvocations(f.manager);
            f.session.scored(finish); f.session.scored(finish);
            var order = inOrder(f.manager);
            order.verify(f.manager).save(); order.verify(f.manager).celebrate(f.position);
            verify(f.manager, times(1)).celebrate(any());
            assertTrue(f.run.racer(f.id).done());
        }
    }
    @Test void failingToSaveTheFinishDoesNotLaunchFireworks() {
        try (var f = new Fixture(true)) {
            f.kit.when(() -> RaceKit.isWeapon(any())).thenReturn(true); f.start();
            doThrow(new IllegalStateException("Save failed")).when(f.manager).save();
            f.session.scored(attack(f.player, f.mobs[1]));
            verify(f.manager, never()).celebrate(any());
        }
    }
    private static EntityDamageByEntityEvent attack(Player player, Entity target) {
        var hit = mock(EntityDamageByEntityEvent.class);
        when(hit.getEntity()).thenReturn(target); when(hit.getDamager()).thenReturn(player);
        when(hit.getFinalDamage()).thenReturn(1d); when(hit.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        return hit;
    }
    private static class Fixture implements AutoCloseable {
        final StateTestServer env = new StateTestServer();
        final MockedStatic<RegistryAccess> registry = mockStatic(RegistryAccess.class);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<RaceKit> kit = mockStatic(RaceKit.class);
        final Player player = mock(Player.class);
        final UUID id = UUID.randomUUID(), opponentId = UUID.randomUUID();
        final Player opponent = mock(Player.class);
        final World world = mock(World.class);
        final RaceManager manager = mock(RaceManager.class);
        final Entity[] mobs;
        final RaceRun run;
        final RaceSession session;
        Location position;
        Fixture(boolean returnOnGround) { this(returnOnGround, false); }
        Fixture(boolean returnOnGround, boolean multiplayer) {
            registry.when(RegistryAccess::registryAccess).thenReturn(mock(RegistryAccess.class, RETURNS_MOCKS));
            var attrs = new HashMap<Key, Attribute>();
            doAnswer(call -> attrs.computeIfAbsent(call.getArgument(0), key -> {
                Attribute a = mock(Attribute.class); when(a.getKey()).thenReturn(new NamespacedKey(key.namespace(), key.value())); return a;
            })).when(Registry.ATTRIBUTE).getOrThrow(any(Key.class));
            when(world.getName()).thenReturn("arena"); when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true); bukkit.when(() -> Bukkit.getWorld("arena")).thenReturn(world);
            Block solid = mock(Block.class), air = mock(Block.class); VoxelShape shape = mock(VoxelShape.class), empty = mock(VoxelShape.class);
            when(shape.getBoundingBoxes()).thenReturn(List.of(new BoundingBox(0, 0, 0, 1, 1, 1))); when(empty.getBoundingBoxes()).thenReturn(List.of());
            when(solid.getCollisionShape()).thenReturn(shape); when(air.getCollisionShape()).thenReturn(empty);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(c -> ((int) c.getArgument(1)) == 63 ? solid : air);
            Chunk chunk = mock(Chunk.class); when(world.getChunkAtAsync(0, 0)).thenReturn(CompletableFuture.completedFuture(chunk)); when(world.getChunkAt(0, 0)).thenReturn(chunk);
            mobs = new Entity[]{mob("#1", 3), mob("Finish", 6)}; when(chunk.getEntities()).thenReturn(mobs);
            ArenaMap map = new ArenaMap("course"); map.setWorldName("arena"); map.setRollbackRegion(0, 60, 0, 10, 80, 10);
            map.setTeam1Spawn(1, 64, 1, 0, 0); map.setTeam2Spawn(1, 64, 1, 0, 0);
            ArenaInstance instance = mock(ArenaInstance.class); when(instance.map()).thenReturn(map); when(instance.cell()).thenReturn(new BlockBounds(0, -64, 0, 100, 319, 100));
            at(1, 64, 1); when(player.getUniqueId()).thenReturn(id); when(player.isOnline()).thenReturn(true); when(player.getMaxHealth()).thenReturn(20d);
            when(player.getGameMode()).thenReturn(GameMode.SURVIVAL); when(player.getInventory()).thenReturn(mock(PlayerInventory.class)); when(player.teleport(any(Location.class))).thenReturn(true);
            when(player.getLocation()).thenAnswer(c -> position.clone()); when(player.getBoundingBox()).thenAnswer(c -> new BoundingBox(position.getX() - .3, position.getY(), position.getZ() - .3, position.getX() + .3, position.getY() + 1.8, position.getZ() + .3));
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            var roster = new ArrayList<RaceRun.Racer>(); roster.add(new RaceRun.Racer(id, "Alice"));
            if (multiplayer) {
                when(opponent.getUniqueId()).thenReturn(opponentId); when(opponent.isOnline()).thenReturn(true);
                when(opponent.getMaxHealth()).thenReturn(20d); when(opponent.getGameMode()).thenReturn(GameMode.SURVIVAL);
                when(opponent.getInventory()).thenReturn(mock(PlayerInventory.class)); when(opponent.teleport(any(Location.class))).thenReturn(true);
                when(opponent.getLocation()).thenAnswer(c -> position.clone()); bukkit.when(() -> Bukkit.getPlayer(opponentId)).thenReturn(opponent);
                roster.add(new RaceRun.Racer(opponentId, "Bob"));
            }
            run = new RaceRun(UUID.randomUUID(), "Race", "course", 15, 0, roster);
            session = new RaceSession(env.plugin, manager, run, new RaceCourse("course", 15, "Finish", 3, returnOnGround), new ArenaLease(instance, UUID.randomUUID()));
            session.enable(); assertEquals(RaceRun.State.COUNTDOWN, run.state());
        }
        void at(double x, double y, double z) { position = new Location(world, x, y, z); }
        void start() { for (int i = 0; i < 10; i++) env.scheduled.getFirst().run(); assertEquals(RaceRun.State.RUNNING, run.state()); }
        LivingEntity mob(String name, int x) {
            var mob = mock(LivingEntity.class); when(mob.getUniqueId()).thenReturn(UUID.randomUUID()); when(mob.customName()).thenReturn(Component.text(name));
            when(mob.getLocation()).thenAnswer(c -> new Location(world, x, 64, 4)); when(mob.getAttribute(any())).thenReturn(mock(AttributeInstance.class));
            when(mob.isValid()).thenReturn(true); when(mob.getMaxHealth()).thenReturn(1024d); return mob;
        }
        @Override public void close() { session.disable(); kit.close(); bukkit.close(); registry.close(); env.close(); }
    }
}
