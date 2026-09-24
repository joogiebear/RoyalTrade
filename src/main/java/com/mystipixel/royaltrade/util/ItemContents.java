package com.mystipixel.royaltrade.util;

import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.List;

/**
 * What an item carries inside it: a bundle's items, or a container block's snapshot inventory (a
 * shulker box, or a chest or barrel picked up with its contents). Slots may be null.
 */
public final class ItemContents {

    private ItemContents() {
    }

    public static List<ItemStack> of(ItemMeta meta) {
        if (meta instanceof BundleMeta bundle) {
            return bundle.getItems();
        }
        if (meta instanceof BlockStateMeta states && states.hasBlockState()
                && states.getBlockState() instanceof Container container) {
            return Arrays.asList(container.getSnapshotInventory().getContents());
        }
        return List.of();
    }
}
