package com.mystipixel.royaltrade.config;

import com.mystipixel.royaltrade.util.ItemContents;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Items that may never be offered in a trade. Entries are a material name ({@code DRAGON_EGG}) or an
 * eco-item id ({@code ecoitems:soulbound_blade}), matched on the persistent-data identity tag so no
 * eco dependency is needed. Containers are searched too.
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
                        + " namespaced item id; ignoring it.");
            }
        }
        return new BlockedItems(materials, List.copyOf(ecoIds));
    }

    /** Why a stack may not be offered. */
    public enum Match {
        NONE,
        ITEM,
        /** Something inside it is, e.g. a shulker box or bundle carrying a blocked item. */
        CONTENTS
    }

    // bundles can sit in shulker boxes and nest, so one level is not enough; the cap bounds the work
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


    static boolean contains(ItemStack stack, Predicate<ItemStack> blocked, int depth) {
        if (depth >= MAX_DEPTH || stack == null || !stack.hasItemMeta()) {
            return false;
        }
        for (ItemStack inner : ItemContents.of(stack.getItemMeta())) {
            if (inner != null && (blocked.test(inner) || contains(inner, blocked, depth + 1))) {
                return true;
            }
        }
        return false;
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
