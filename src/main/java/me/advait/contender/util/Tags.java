package me.advait.contender.util;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

/** Marks entities that Contender spawned, so protection listeners and cleanup can recognise them. */
public final class Tags {
    public static final NamespacedKey MANAGED = new NamespacedKey("contender", "managed");
    private Tags() { }

    /** Tag with the owning feature, for example "race" or "vote". */
    public static <T extends Entity> T managed(T entity, String owner) {
        entity.getPersistentDataContainer().set(MANAGED, PersistentDataType.STRING, owner);
        entity.setPersistent(false);
        return entity;
    }

    public static boolean isManaged(Entity entity) {
        return entity.getPersistentDataContainer().has(MANAGED, PersistentDataType.STRING);
    }

    public static String owner(Entity entity) {
        return entity.getPersistentDataContainer().get(MANAGED, PersistentDataType.STRING);
    }
}
