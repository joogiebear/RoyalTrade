package com.mystipixel.royaltrade.data;

import com.mystipixel.royaltrade.util.BackgroundWriter;
import com.mystipixel.royaltrade.util.ItemContents;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
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
import java.util.logging.Logger;

/**
 * An append-only, greppable record of every completed trade, for settling disputes. Written after the
 * commit, so a failure is logged and the trade stands.
 */
public final class TradeLog {

    private static final long CLOSE_TIMEOUT_MILLIS = 5_000L;

    private final BackgroundWriter<String> writer;

    public TradeLog(File dataFolder, Logger logger) {
        File file = new File(dataFolder, "trades.log");
        // lines queued while a write runs are appended together
        this.writer = new BackgroundWriter<>("trades.log", "RoyalTrade-log-writer",
                lines -> Files.writeString(file.toPath(), lines, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                (queued, newer) -> queued + newer, logger);
    }

    /** Waits for queued lines to reach disk. Call from onDisable. */
    public void close() {
        writer.close(CLOSE_TIMEOUT_MILLIS);
    }

    public void record(UUID sessionId, Player a, Player b,
                       List<ItemStack> aGave, List<ItemStack> bGave,
                       double aCoins, double bCoins) {
        String line = String.format(Locale.ROOT, "%s id=%s %s(%s) gave [%s] + %.2f  <->  %s(%s) gave [%s] + %.2f%n",
                Instant.now(), sessionId == null ? "?" : sessionId,
                a.getName(), a.getUniqueId(), describe(aGave), aCoins,
                b.getName(), b.getUniqueId(), describe(bGave), bCoins);
        writer.submit(line);
    }

    /**
     * Items as {@code 1x NETHERITE_SWORD "Doombringer" {sharpness 5}, 1x SHULKER_BOX [contains: ...]}.
     * Also used for EconGuard's ledger.
     */
    public static String describe(List<ItemStack> items) {
        if (items.isEmpty()) {
            return "nothing";
        }
        StringBuilder sb = new StringBuilder();
        describeAll(sb, items, 0);
        return sb.toString();
    }

    // how far into nested containers the log follows; deeper is summarised
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
