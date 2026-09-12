package me.advait.contender.race;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RaceGroundingTest {
    @Test void theStartIsSafeUntilTakeoffAndBlockedReturnsStayArmed() {
        var grounding = new RaceGrounding(); UUID id = UUID.randomUUID(), other = UUID.randomUUID();
        assertFalse(grounding.landed(id, true)); assertFalse(grounding.landed(id, true));
        assertFalse(grounding.landed(id, false)); assertFalse(grounding.landed(other, true));
        assertTrue(grounding.landed(id, true)); assertTrue(grounding.landed(id, true));
        grounding.reset(id); assertFalse(grounding.landed(id, true));
        assertFalse(grounding.landed(id, false)); grounding.clear(); assertFalse(grounding.landed(id, true));
    }
    @Test void detectsFullBlocksSlabsAndTallFenceShapes() {
        World world = world();
        block(world, 0, 63, 0, new BoundingBox(0, 0, 0, 1, 1, 1));
        assertTrue(RaceGrounding.supported(world, player(64)));
        assertFalse(RaceGrounding.supported(world, player(64.1)));
        block(world, 0, 64, 0, new BoundingBox(0, 0, 0, 1, .5, 1));
        assertTrue(RaceGrounding.supported(world, player(64.5)));
        block(world, 0, 64, 0);
        block(world, 0, 63, 0, new BoundingBox(.375, 0, .375, .625, 1.5, .625));
        assertTrue(RaceGrounding.supported(world, player(64.5)));
    }
    @Test void airAndSideContactsDoNotCountAsGroundAndChunksAreNotLoaded() {
        World world = world();
        assertFalse(RaceGrounding.supported(world, player(64)));
        block(world, 0, 63, 0, new BoundingBox(.7, 0, 0, 1, 2, 1));
        assertFalse(RaceGrounding.supported(world, player(64))); // wall extends above feet
        block(world, 0, 63, 0, new BoundingBox(0, 0, 0, 1, 1, 1));
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        clearInvocations(world); assertFalse(RaceGrounding.supported(world, player(64)));
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }
    private World world() {
        World world = mock(World.class); when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        Block air = mock(Block.class); VoxelShape empty = mock(VoxelShape.class);
        when(empty.getBoundingBoxes()).thenReturn(List.of()); when(air.getCollisionShape()).thenReturn(empty);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(air); return world;
    }
    private void block(World world, int x, int y, int z, BoundingBox... boxes) {
        Block block = mock(Block.class); VoxelShape shape = mock(VoxelShape.class);
        when(shape.getBoundingBoxes()).thenReturn(List.of(boxes)); when(block.getCollisionShape()).thenReturn(shape);
        when(world.getBlockAt(x, y, z)).thenReturn(block);
    }
    private BoundingBox player(double feet) { return new BoundingBox(.2, feet, .2, .8, feet + 1.8, .8); }
}
