package me.advait.contender.race;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import me.advait.contender.testutil.StateTestServer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RaceEditorToolsTest {
    @Test void eitherMouseButtonRemovesOnlyTheClickedEntityFromTheToolsMap() {
        try (var fixture = new Fixture()) {
            fixture.hold(tool("course_a", fixture.owner));
            Entity rightTarget = target("#2");
            Entity leftTarget = target("#5");
            var right = fixture.interact(rightTarget, EquipmentSlot.HAND);
            var left = new PrePlayerAttackEntityEvent(fixture.player, leftTarget, true);

            fixture.tools.interact(right);
            fixture.tools.attack(left);

            assertTrue(right.isCancelled());
            assertTrue(left.isCancelled());
            verify(fixture.manager).removeCourseMob(fixture.player, "course_a", rightTarget);
            verify(fixture.manager).removeCourseMob(fixture.player, "course_a", leftTarget);
            verify(fixture.manager, times(2)).removeCourseMob(any(), anyString(), any());
            verify(rightTarget, never()).remove();
            verify(leftTarget, never()).remove();
        }
    }

    @Test void ordinaryShearsKeepTheirNormalInteractions() {
        try (var fixture = new Fixture()) {
            ItemStack ordinary = mock(ItemStack.class);
            when(ordinary.getType()).thenReturn(Material.SHEARS);
            fixture.hold(ordinary);
            Entity target = target("#2");
            var right = fixture.interact(target, EquipmentSlot.HAND);
            var left = new PrePlayerAttackEntityEvent(fixture.player, target, true);

            fixture.tools.interact(right);
            fixture.tools.attack(left);

            assertFalse(right.isCancelled());
            assertFalse(left.isCancelled());
            verifyNoInteractions(fixture.manager);
        }
    }

    @Test void anotherPlayersToolCannotRemoveAnything() {
        try (var fixture = new Fixture()) {
            fixture.hold(tool("course_a", UUID.randomUUID()));
            Entity target = target("#2");
            var right = fixture.interact(target, EquipmentSlot.HAND);
            var left = new PrePlayerAttackEntityEvent(fixture.player, target, true);

            fixture.tools.interact(right);
            fixture.tools.attack(left);

            assertTrue(right.isCancelled());
            assertTrue(left.isCancelled());
            verify(fixture.manager, never()).removeCourseMob(any(), anyString(), any());
        }
    }

    @Test void offhandPacketsAndAlreadyRemovedTargetsCannotDeleteMobs() {
        try (var fixture = new Fixture()) {
            ItemStack tool = tool("course_a", fixture.owner);
            fixture.hold(tool);
            when(fixture.inventory.getItem(EquipmentSlot.OFF_HAND)).thenReturn(tool);
            Entity target = target("#2");
            var offhand = fixture.interact(target, EquipmentSlot.OFF_HAND);
            fixture.tools.interact(offhand);
            assertTrue(offhand.isCancelled());

            when(target.isValid()).thenReturn(false);
            fixture.tools.attack(new PrePlayerAttackEntityEvent(fixture.player, target, true));

            verify(fixture.manager, never()).removeCourseMob(any(), anyString(), any());
        }
    }

    @Test void markingTheFinishTakesPrecedenceOverTheHeldRemovalTool() {
        try (var fixture = new Fixture()) {
            fixture.hold(tool("course_a", fixture.owner));
            when(fixture.manager.markingFinish(fixture.player)).thenReturn(true);
            Entity target = target("#2");
            var right = fixture.interact(target, EquipmentSlot.HAND);
            var left = new PrePlayerAttackEntityEvent(fixture.player, target, true);

            fixture.tools.interact(right);
            fixture.tools.attack(left);

            assertFalse(right.isCancelled(), "The finish-selection listener must receive the right click");
            assertTrue(left.isCancelled(), "The held tool must not deal ordinary attack damage");
            verify(fixture.manager, never()).removeCourseMob(any(), anyString(), any());
        }
    }

    @Test void removalToolsCannotBreakOrUseBlocks() {
        try (var fixture = new Fixture()) {
            ItemStack tool = tool("course_a", fixture.owner);
            fixture.hold(tool);
            var broken = new BlockBreakEvent(mock(org.bukkit.block.Block.class), fixture.player);
            var used = new PlayerInteractEvent(fixture.player, Action.RIGHT_CLICK_BLOCK, tool,
                    mock(org.bukkit.block.Block.class), org.bukkit.block.BlockFace.UP, EquipmentSlot.HAND);

            fixture.tools.blockBreak(broken);
            fixture.tools.blockUse(used);

            assertTrue(broken.isCancelled());
            assertTrue(used.isCancelled());
            verifyNoInteractions(fixture.manager);
        }
    }

    @Test void fullInventoryRejectsTheToolWithoutOverwritingAnything() {
        try (var fixture = new Fixture()) {
            Arrays.fill(fixture.storage, mock(ItemStack.class));
            fixture.storage[9] = tool("course_b", UUID.randomUUID());
            ItemStack[] before = fixture.storage.clone();
            when(fixture.inventory.firstEmpty()).thenReturn(-1);

            assertThrows(IllegalStateException.class,
                    () -> fixture.tools.give(fixture.player, "course_a", "Course A"));

            assertArrayEquals(before, fixture.storage);
            verify(fixture.inventory, never()).setItem(anyInt(), any());
            verify(fixture.player, never()).closeDialog();
        }
    }

    @Test void receivingAnotherToolReusesOnlyTheOwnersExistingSlotEvenInAFullInventory() {
        try (var fixture = new Fixture(); var created = toolConstruction()) {
            Arrays.fill(fixture.storage, mock(ItemStack.class));
            ItemStack foreign = tool("course_a", UUID.randomUUID());
            fixture.storage[2] = foreign;
            fixture.storage[8] = tool("old_course", fixture.owner);
            ItemStack[] before = fixture.storage.clone();
            when(fixture.inventory.firstEmpty()).thenReturn(-1);

            fixture.tools.give(fixture.player, "new_course", "New Course");

            ItemStack replacement = created.constructed().getFirst();
            assertEquals(1, created.constructed().size());
            assertSame(replacement, fixture.storage[8]);
            for (int slot = 0; slot < before.length; slot++) {
                if (slot != 8) assertSame(before[slot], fixture.storage[slot]);
            }
            assertEquals("new_course", replacement.getItemMeta().getPersistentDataContainer()
                    .get(RaceEditorTools.MAP, PersistentDataType.STRING));
            assertEquals(fixture.owner.toString(), replacement.getItemMeta().getPersistentDataContainer()
                    .get(RaceEditorTools.OWNER, PersistentDataType.STRING));
            verify(replacement.getItemMeta()).setUnbreakable(true);
            verify(fixture.inventory).setItem(8, replacement);
            verify(fixture.player).closeDialog();
        }
    }

    private static Entity target(String name) {
        Entity entity = mock(Entity.class);
        when(entity.isValid()).thenReturn(true);
        when(entity.customName()).thenReturn(Component.text(name));
        return entity;
    }

    private static ItemStack tool(String map, UUID owner) {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(item.getType()).thenReturn(Material.SHEARS);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.get(RaceEditorTools.MAP, PersistentDataType.STRING)).thenReturn(map);
        when(data.get(RaceEditorTools.OWNER, PersistentDataType.STRING)).thenReturn(owner.toString());
        return item;
    }

    @SuppressWarnings("unchecked")
    private static MockedConstruction<ItemStack> toolConstruction() {
        return mockConstruction(ItemStack.class, (item, context) -> {
            assertEquals(Material.SHEARS, context.arguments().getFirst());
            ItemMeta meta = mock(ItemMeta.class);
            PersistentDataContainer data = mock(PersistentDataContainer.class);
            Map<NamespacedKey, String> tags = new HashMap<>();
            when(item.getItemMeta()).thenReturn(meta);
            when(meta.getPersistentDataContainer()).thenReturn(data);
            doAnswer(call -> { tags.put(call.getArgument(0), call.getArgument(2)); return null; })
                    .when(data).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), anyString());
            when(data.get(any(NamespacedKey.class), eq(PersistentDataType.STRING)))
                    .thenAnswer(call -> tags.get(call.getArgument(0)));
            when(item.editMeta(any(Consumer.class))).thenAnswer(call -> {
                ((Consumer<ItemMeta>) call.getArgument(0)).accept(meta);
                return true;
            });
        });
    }

    private static final class Fixture implements AutoCloseable {
        private final StateTestServer env = new StateTestServer();
        private final RaceManager manager = mock(RaceManager.class);
        private final Player player = mock(Player.class);
        private final PlayerInventory inventory = mock(PlayerInventory.class);
        private final UUID owner = UUID.randomUUID();
        private final ItemStack[] storage = new ItemStack[36];
        private final RaceEditorTools tools = new RaceEditorTools(env.plugin, manager);

        private Fixture() {
            when(player.getUniqueId()).thenReturn(owner);
            when(player.isOnline()).thenReturn(true);
            when(player.getInventory()).thenReturn(inventory);
            when(inventory.getStorageContents()).thenReturn(storage);
            doAnswer(call -> { storage[call.getArgument(0)] = call.getArgument(1); return null; })
                    .when(inventory).setItem(anyInt(), any());
            when(manager.removeCourseMob(any(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
            tools.enable();
        }

        private void hold(ItemStack item) {
            when(inventory.getItemInMainHand()).thenReturn(item);
            when(inventory.getItem(EquipmentSlot.HAND)).thenReturn(item);
        }

        private PlayerInteractEntityEvent interact(Entity target, EquipmentSlot hand) {
            return new PlayerInteractEntityEvent(player, target, hand);
        }

        @Override public void close() { tools.disable(); env.close(); }
    }
}
