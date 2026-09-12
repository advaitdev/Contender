package me.advait.contender.arena;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.registry.BlockMaterial;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Conservative restart reuse: dynamic contents must be pasted from the template again. */
final class ArenaTemplateSafety {
    private ArenaTemplateSafety() { }

    /** Reads clipboard data only. Run once on the template worker, never during shutdown. */
    static boolean cacheable(Clipboard clipboard) {
        if (clipboard.getEntities() == null || !clipboard.getEntities().isEmpty()) return false;
        Set<BlockType> checked = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var position : clipboard) {
            var state = clipboard.getBlock(position);
            if (state == null || state.getBlockType() == null) return false;
            BlockType type = state.getBlockType();
            if (checked.add(type) && !stable(type.getMaterial())) return false;
        }
        return true;
    }

    static boolean stable(BlockMaterial material) {
        return material != null && !material.isTile() && !material.hasContainer()
                && !material.isTicksRandomly() && !material.isPowerSource() && !material.isLiquid();
    }
}
