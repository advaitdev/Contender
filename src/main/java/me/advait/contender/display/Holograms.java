package me.advait.contender.display;

import me.advait.contender.util.Tags;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Spawns display entities with consistent defaults. All of them are non-persistent and tagged as managed. */
public final class Holograms {
    private Holograms() { }

    public static TextDisplay text(Location at, Component text, float scale, Display.Billboard billboard, Color background, String owner) {
        return at.getWorld().spawn(at, TextDisplay.class, display -> {
            Tags.managed(display, owner);
            display.text(text);
            display.setBillboard(billboard);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setShadowed(true);
            display.setDefaultBackground(false);
            display.setBackgroundColor(background);
            display.setLineWidth(400);
            display.setViewRange(2f);
            display.setTransformation(scaled(scale));
            display.setTeleportDuration(2);
        });
    }

    public static BlockDisplay block(Location at, BlockData block, Transformation transformation, String owner) {
        return at.getWorld().spawn(at, BlockDisplay.class, display -> {
            Tags.managed(display, owner);
            display.setBlock(block);
            display.setTransformation(transformation);
            display.setViewRange(2f);
            display.setTeleportDuration(2);
        });
    }

    public static ItemDisplay item(Location at, ItemStack item, Transformation transformation, String owner) {
        return at.getWorld().spawn(at, ItemDisplay.class, display -> {
            Tags.managed(display, owner);
            display.setItemStack(item);
            display.setTransformation(transformation);
            display.setBillboard(Display.Billboard.FIXED);
            display.setViewRange(2f);
            display.setTeleportDuration(2);
        });
    }

    public static Transformation scaled(float scale) {
        return new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f());
    }

    public static Transformation transform(float x, float y, float z, float scale, float yawDegrees) {
        return new Transformation(new Vector3f(x, y, z), new AxisAngle4f((float) Math.toRadians(yawDegrees), 0, 1, 0),
                new Vector3f(scale, scale, scale), new AxisAngle4f());
    }

    public static Transformation box(float width, float height, float depth, float yawDegrees) {
        // Centre the block model on the entity position before rotating it.
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(yawDegrees));
        Vector3f offset = new Vector3f(-width / 2, 0, -depth / 2).rotate(rotation);
        return new Transformation(offset, rotation, new Vector3f(width, height, depth), new Quaternionf());
    }

    /** Smoothly changes a display's transformation on the client over the given ticks. */
    public static void animate(Display display, Transformation target, int ticks) {
        if (!display.isValid()) return;
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(Math.max(1, ticks));
        display.setTransformation(target);
    }

    public static void remove(Display display) {
        if (display != null && display.isValid()) display.remove();
    }
}
