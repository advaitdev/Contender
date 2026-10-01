package me.advait.contender.minigame.race;

import me.advait.contender.util.Tags;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;

import java.util.List;

/** Spawns frozen, silent checkpoint mobs. They never move, burn, attack, or despawn on their own. */
public final class RaceMobs {
    /** Mob types offered by the editor, in cycling order. */
    public static final List<EntityType> TYPES = List.of(EntityType.ZOMBIE, EntityType.HUSK, EntityType.SKELETON, EntityType.CREEPER,
            EntityType.PILLAGER, EntityType.WITCH, EntityType.VILLAGER, EntityType.IRON_GOLEM, EntityType.BLAZE, EntityType.PIG,
            EntityType.COW, EntityType.SHEEP, EntityType.SLIME, EntityType.MAGMA_CUBE);

    private RaceMobs() { }

    public static LivingEntity spawn(Location at, EntityType type, Component label, String owner, boolean preview) {
        Class<? extends Entity> entityClass = type.getEntityClass();
        if (entityClass == null || !LivingEntity.class.isAssignableFrom(entityClass)) {
            type = EntityType.ZOMBIE;
            entityClass = type.getEntityClass();
        }
        if (at.getWorld().getDifficulty() == Difficulty.PEACEFUL && Enemy.class.isAssignableFrom(entityClass)) {
            // Hostile mobs disappear in Peaceful.
            at.getWorld().setDifficulty(Difficulty.EASY);
        }
        Entity spawned = at.getWorld().spawn(at, entityClass, entity -> {
            Tags.managed(entity, owner);
            LivingEntity mob = (LivingEntity) entity;
            mob.setAI(false);
            mob.setGravity(false);
            mob.setSilent(true);
            mob.setCollidable(false);
            mob.setCanPickupItems(false);
            mob.setRemoveWhenFarAway(false);
            mob.customName(label);
            mob.setCustomNameVisible(true);
            mob.setInvulnerable(preview);
            mob.setGlowing(preview);
            if (mob.getEquipment() != null) mob.getEquipment().clear();
            if (mob instanceof Ageable ageable) ageable.setAdult();
            if (mob instanceof Zombie zombie) zombie.setShouldBurnInDay(false);
            if (mob instanceof AbstractSkeleton skeleton) skeleton.setShouldBurnInDay(false);
            if (mob instanceof Slime slime) slime.setSize(2);
            if (mob instanceof Creeper creeper) creeper.setMaxFuseTicks(Integer.MAX_VALUE);
            var health = mob.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) { health.setBaseValue(1024); mob.setHealth(1024); }
            var knockback = mob.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            if (knockback != null) knockback.setBaseValue(1);
        });
        return (LivingEntity) spawned;
    }

    public static Component label(String text, TextColor color) { return Component.text(text, color); }
}
