package me.advait.contender.arena;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.registry.BlockMaterial;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArenaTemplateSafetyTest {
    @Test void staticBlocksCanBeReusedAndEachBlockTypeIsCheckedOnce() {
        Clipboard clipboard = mock(Clipboard.class);
        BlockType stone = mock(BlockType.class);
        BlockState state = mock(BlockState.class);
        BlockMaterial material = mock(BlockMaterial.class);
        when(stone.getMaterial()).thenReturn(material);
        when(state.getBlockType()).thenReturn(stone);
        when(clipboard.getBlock(any(BlockVector3.class))).thenReturn(state);
        when(clipboard.iterator()).thenReturn(List.of(BlockVector3.at(0, 0, 0), BlockVector3.at(1, 0, 0)).iterator());

        assertTrue(ArenaTemplateSafety.cacheable(clipboard));

        verify(stone, times(1)).getMaterial();
    }

    @Test void EntitiesSkipTheBlockScanEntirely() {
        Clipboard clipboard = mock(Clipboard.class);
        doReturn(List.of(mock(com.sk89q.worldedit.entity.Entity.class))).when(clipboard).getEntities();

        assertFalse(ArenaTemplateSafety.cacheable(clipboard));

        verify(clipboard, never()).iterator();
    }

    @Test void dynamicMaterialsAreConservativelyExcluded() {
        List<Consumer<BlockMaterial>> dynamic = List.of(
                material -> when(material.isTile()).thenReturn(true),
                material -> when(material.hasContainer()).thenReturn(true),
                material -> when(material.isTicksRandomly()).thenReturn(true),
                material -> when(material.isPowerSource()).thenReturn(true),
                material -> when(material.isLiquid()).thenReturn(true));
        for (Consumer<BlockMaterial> change : dynamic) {
            BlockMaterial material = mock(BlockMaterial.class);
            change.accept(material);
            assertFalse(ArenaTemplateSafety.stable(material));
        }
        assertFalse(ArenaTemplateSafety.stable(null));
    }

    @Test void scanStopsAtTheFirstDynamicBlock() {
        Clipboard clipboard = mock(Clipboard.class);
        BlockType crop = mock(BlockType.class);
        BlockState state = mock(BlockState.class);
        BlockMaterial material = mock(BlockMaterial.class);
        when(material.isTicksRandomly()).thenReturn(true);
        when(crop.getMaterial()).thenReturn(material);
        when(state.getBlockType()).thenReturn(crop);
        when(clipboard.getBlock(any(BlockVector3.class))).thenReturn(state);
        when(clipboard.iterator()).thenReturn(List.of(BlockVector3.at(0, 0, 0), BlockVector3.at(1, 0, 0)).iterator());

        assertFalse(ArenaTemplateSafety.cacheable(clipboard));

        verify(clipboard, times(1)).getBlock(any(BlockVector3.class));
    }

    @Test void unknownBlockMaterialDoesNotCountAsSafe() {
        Clipboard clipboard = mock(Clipboard.class);
        BlockType unknown = mock(BlockType.class);
        BlockState state = mock(BlockState.class);
        when(state.getBlockType()).thenReturn(unknown);
        when(clipboard.getBlock(any(BlockVector3.class))).thenReturn(state);
        when(clipboard.iterator()).thenReturn(List.of(BlockVector3.at(0, 0, 0)).iterator());

        assertFalse(ArenaTemplateSafety.cacheable(clipboard));
    }
}
