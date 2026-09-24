package com.mystipixel.royaltrade.config;

import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.junit.jupiter.api.*;
import java.util.List;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The container search. Materials need a booted registry, so "blocked" here is one marked stack. */
class BlockedItemsTest {
    final ItemStack egg = mock(ItemStack.class);
    final Predicate<ItemStack> isEgg = stack -> stack == egg;

    private static ItemStack shulkerHolding(ItemStack... contents) {
        ItemStack box = mock(ItemStack.class);
        BlockStateMeta meta = mock(BlockStateMeta.class);
        ShulkerBox state = mock(ShulkerBox.class);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getContents()).thenReturn(contents);
        when(state.getSnapshotInventory()).thenReturn(inventory);
        when(meta.hasBlockState()).thenReturn(true);
        when(meta.getBlockState()).thenReturn(state);
        when(box.hasItemMeta()).thenReturn(true);
        when(box.getItemMeta()).thenReturn(meta);
        return box;
    }
    private static ItemStack bundleHolding(ItemStack... contents) {
        ItemStack bundle = mock(ItemStack.class);
        BundleMeta meta = mock(BundleMeta.class);
        when(meta.getItems()).thenReturn(List.of(contents));
        when(bundle.hasItemMeta()).thenReturn(true);
        when(bundle.getItemMeta()).thenReturn(meta);
        return bundle;
    }

    @Test void blockedItemInsideAShulkerBoxIsFound() {
        assertTrue(BlockedItems.contains(shulkerHolding(null, mock(ItemStack.class), egg), isEgg, 0));
    }
    @Test void blockedItemInABundleInsideAShulkerBoxIsFound() {
        assertTrue(BlockedItems.contains(shulkerHolding(bundleHolding(egg)), isEgg, 0));
    }
    @Test void harmlessContainersPass() {
        assertFalse(BlockedItems.contains(shulkerHolding(mock(ItemStack.class), null), isEgg, 0));
        assertFalse(BlockedItems.contains(bundleHolding(mock(ItemStack.class)), isEgg, 0));
        assertFalse(BlockedItems.contains(mock(ItemStack.class), isEgg, 0));
    }
    @Test void nestingIsBounded() {
        ItemStack deep = egg;
        for (int i = 0; i < 20; i++) {
            deep = bundleHolding(deep);
        }
        assertFalse(BlockedItems.contains(deep, isEgg, 0));
    }
}
