package com.mystipixel.royaltrade.data;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
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
        String line = String.format("%s id=%s %s(%s) gave [%s] + %.2f  <->  %s(%s) gave [%s] + %.2f%n",
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
     * Items as "3x DIAMOND, 1x NETHERITE_SWORD". Custom names are included when present, because a
     * dispute is usually about a specific named item rather than the material.
     */
    private String describe(List<ItemStack> items) {
        if (items.isEmpty()) {
            return "nothing";
        }
        StringBuilder sb = new StringBuilder();
        for (ItemStack stack : items) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(stack.getAmount()).append("x ").append(stack.getType().name());
            if (stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()) {
                sb.append(" \"").append(stack.getItemMeta().getDisplayName()).append('"');
            }
        }
        return sb.toString();
    }
}
