package com.mystipixel.royaltrade.config;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Items that may never be offered in a trade — quest items, soulbound gear, seasonal rewards.
 *
 * <p>Entries are either a material name ({@code DRAGON_EGG}) or an eco-item id
 * ({@code ecoitems:soulbound_blade}). Eco ids are matched by reading the item's persistent-data
 * identity tag directly — the same keys RoyalBank's upgrade costs read — so no eco dependency is
 * needed and the check works whether or not the eco suite is installed.
 *
 * <p>Shulker boxes, bundles and other containers are searched too, since otherwise any entry is
 * bypassed by packing the item in one.
 */
public final class BlockedItems {

    private final Set<Material> materials;
    private final List<String[]> ecoIds;   // {namespace, id}, both lower-case

    private BlockedItems(Set<Material> materials, List<String[]> ecoIds) {
        this.materials = materials;
        this.ecoIds = ecoIds;
    }

    public static BlockedItems parse(List<String> entries, Logger logger) {
        Set<Material> materials = EnumSet.noneOf(Material.class);
        List<String[]> ecoIds = new ArrayList<>();
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String entry = raw.trim();
            if (entry.contains(":") && !entry.toLowerCase(Locale.ROOT).startsWith("minecraft:")) {
                String[] parts = entry.toLowerCase(Locale.ROOT).split(":", 2);
                ecoIds.add(new String[]{parts[0], parts[1]});
                continue;
            }
            Material material = Material.matchMaterial(entry);
            if (material != null) {
                materials.add(material);
            } else {
                logger.warning("blocked-items entry '" + entry + "' is neither a material nor a"
                        + " namespaced item id — ignoring it.");
            }
        }
        return new BlockedItems(materials, List.copyOf(ecoIds));
    }

    /** Why a stack may not be offered. */
    public enum Match {
        /** Nothing blocked. */
        NONE,
        /** The stack itself is on the list. */
        ITEM,
        /** Something inside it is — a shulker box or bundle carrying a blocked item. */
        CONTENTS
    }

    /**
     * How deep to look inside containers. Vanilla stops a shulker box holding another one, but a
     * bundle can sit in a shulker box and bundles can nest, so one level is not enough; a cap still
     * bounds the work on a hand-crafted item.
     */
    private static final int MAX_DEPTH = 8;

    /** Whether this stack, or anything packed inside it, may not be offered. */
    public Match check(ItemStack stack) {
        if (materials.isEmpty() && ecoIds.isEmpty()) {
            return Match.NONE;
        }
        if (matchesItself(stack)) {
            return Match.ITEM;
        }
        return contains(stack, this::matchesItself, 0) ? Match.CONTENTS : Match.NONE;
    }

    /**
     * Whether anything packed inside {@code stack} matches. Without this, the list is one shulker box
     * away from meaningless: pack the soulbound blade in a box and the box is what gets checked.
     */
    static boolean contains(ItemStack stack, Predicate<ItemStack> blocked, int depth) {
        if (depth >= MAX_DEPTH || stack == null || !stack.hasItemMeta()) {
            return false;
        }
        for (ItemStack inner : contents(stack.getItemMeta())) {
            if (inner != null && (blocked.test(inner) || contains(inner, blocked, depth + 1))) {
                return true;
            }
        }
        return false;
    }

    /** What an item carries: a bundle's items, or a container block's snapshot inventory. */
    private static List<ItemStack> contents(ItemMeta meta) {
        if (meta instanceof BundleMeta bundle) {
            return bundle.getItems();
        }
        if (meta instanceof BlockStateMeta states && states.hasBlockState()
                && states.getBlockState() instanceof Container container) {
            return Arrays.asList(container.getSnapshotInventory().getContents());
        }
        return List.of();
    }

    private boolean matchesItself(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (materials.contains(stack.getType())) {
            return true;
        }
        if (ecoIds.isEmpty() || !stack.hasItemMeta()) {
            return false;
        }
        PersistentDataContainer container = stack.getItemMeta().getPersistentDataContainer();
        for (String[] wanted : ecoIds) {
            String value = container.get(new NamespacedKey(wanted[0], "item"), PersistentDataType.STRING);
            if (value == null && wanted[0].equals("ecoitems")) {
                // Legacy EcoWeapons tag, as RoyalBank's requirement matching also tolerates.
                value = container.get(new NamespacedKey("ecoweapons", "weapon"), PersistentDataType.STRING);
            }
            if (value == null) {
                continue;
            }
            String normalized = value.toLowerCase(Locale.ROOT);
            if (normalized.equals(wanted[1]) || normalized.equals(wanted[0] + ":" + wanted[1])) {
                return true;
            }
        }
        return false;
    }
}
