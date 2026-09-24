package com.mystipixel.royaltrade.data;

import com.mystipixel.royaltrade.util.ItemContents;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An append-only record of every completed trade.
 *
 * <p>This exists for one purpose: answering "he scammed me" three days later. That question is asked
 * about a specific pair of players at a specific time, so a flat file that an admin can grep beats a
 * database they would have to query — and it costs no dependency and cannot be corrupted by a crash
 * mid-write beyond losing the last line.
 *
 * <p>Written after the trade has already committed, so a failure here is logged and swallowed: a
 * missing audit line is a worse outcome than a rolled-back trade, but not by enough to justify
 * undoing goods that both players have already seen arrive.
 */
public final class TradeLog {

    private final File file;
    private final Logger logger;

    public TradeLog(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "trades.log");
        this.logger = logger;
    }

    public void record(UUID sessionId, Player a, Player b,
                       List<ItemStack> aGave, List<ItemStack> bGave,
                       double aCoins, double bCoins) {
        String line = String.format(Locale.ROOT, "%s id=%s %s(%s) gave [%s] + %.2f  <->  %s(%s) gave [%s] + %.2f%n",
                Instant.now(), sessionId == null ? "?" : sessionId,
                a.getName(), a.getUniqueId(), describe(aGave), aCoins,
                b.getName(), b.getUniqueId(), describe(bGave), bCoins);
        try {
            Files.writeString(file.toPath(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not append to trades.log — this trade has no audit "
                    + "record, though it did complete.", e);
        }
    }

    /**
     * Items as {@code 1x NETHERITE_SWORD "Doombringer" {sharpness 5}, 1x SHULKER_BOX [contains: ...]}.
     *
     * <p>A dispute is about a specific item — "the sword had Sharpness V", "the box was full of
     * diamonds" — so the name, enchantments and anything packed inside go on the line, not just the
     * material. Public so EconGuard's ledger can carry the same description.
     */
    public static String describe(List<ItemStack> items) {
        if (items.isEmpty()) {
            return "nothing";
        }
        StringBuilder sb = new StringBuilder();
        describeAll(sb, items, 0);
        return sb.toString();
    }

    /** How far into nested containers the log follows. Deeper than this is summarised. */
    private static final int MAX_DEPTH = 3;

    private static void describeAll(StringBuilder sb, List<ItemStack> items, int depth) {
        boolean first = true;
        for (ItemStack stack : items) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            first = false;
            describeOne(sb, stack, depth);
        }
    }

    private static void describeOne(StringBuilder sb, ItemStack stack, int depth) {
        sb.append(stack.getAmount()).append("x ").append(stack.getType().name());
        if (!stack.hasItemMeta()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta.hasDisplayName() && meta.displayName() != null) {
            sb.append(" \"").append(PlainTextComponentSerializer.plainText().serialize(meta.displayName()))
                    .append('"');
        }
        Map<Enchantment, Integer> enchants = new LinkedHashMap<>(meta.getEnchants());
        if (meta instanceof EnchantmentStorageMeta book) {
            enchants.putAll(book.getStoredEnchants());
        }
        if (!enchants.isEmpty()) {
            StringJoiner joined = new StringJoiner(", ", " {", "}");
            enchants.forEach((enchant, level) -> joined.add(enchant.getKey().getKey() + " " + level));
            sb.append(joined);
        }
        List<ItemStack> inside = ItemContents.of(meta).stream()
                .filter(inner -> inner != null && !inner.getType().isAir())
                .toList();
        if (!inside.isEmpty()) {
            if (depth >= MAX_DEPTH) {
                sb.append(" [contains ").append(inside.size()).append(" more stack(s)]");
            } else {
                sb.append(" [contains: ");
                describeAll(sb, inside, depth + 1);
                sb.append(']');
            }
        }
    }
}
