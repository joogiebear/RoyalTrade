package com.mystipixel.royaltrade.hooks;

import com.mystipixel.royaltrade.data.TradeLog;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * Reports completed trades to EconGuard.
 *
 * <p>Wired by reflection against EconGuard's flat {@code EconGuard.record(...)} bridge, so this
 * plugin carries no build-time dependency and runs fine without it.
 *
 * <p>This matters more here than anywhere else in the suite. EconGuard watches for wealth moving to
 * young accounts and for unusual velocity — the shapes that real-money trading and alt-funnelling
 * make. Player-to-player trade is the most direct way to move wealth between accounts, so a trade
 * plugin that does not report is the hole every other check gets routed around. Unlike the bazaar,
 * both sides of a trade are players, so the counterparty fields are filled in.
 *
 * <p>It also consults EconGuard's {@code allowTrade} veto before a trade is requested, accepted and
 * completed. That refuses flagged players when EconGuard's {@code enforcement.block-flagged-trades}
 * is on and permits everyone otherwise; on an EconGuard old enough to lack the veto, or if the call
 * fails, it permits — an audit outage must not stop trading.
 */
public final class EconGuardHook {

    private static final String SOURCE = "trade";

    /** The ledger's item column is VARCHAR(255) on MySQL; longer descriptions are cut to fit. */
    private static final int ITEM_LIMIT = 255;

    private final Method bridge;
    private final Method veto;

    public EconGuardHook() {
        Method resolved = null;
        Method resolvedVeto = null;
        if (Bukkit.getPluginManager().isPluginEnabled("EconGuard")) {
            try {
                Class<?> econGuard = Class.forName("com.mystipixel.econguard.api.EconGuard");
                resolved = econGuard.getMethod("record",
                        UUID.class, String.class, String.class, String.class,
                        double.class, boolean.class, double.class,
                        UUID.class, String.class, String.class, String.class);
                try {
                    resolvedVeto = econGuard.getMethod("allowTrade", UUID.class);
                } catch (NoSuchMethodException oldEconGuard) {
                    // Predates the veto bridge — reporting still works, allow() permits.
                }
            } catch (Throwable ignored) {
                // EconGuard absent, or older than the bridge — stay a no-op.
            }
        }
        this.bridge = resolved;
        this.veto = resolvedVeto;
    }

    public boolean isPresent() {
        return bridge != null;
    }

    /** Whether EconGuard lets this player trade right now. Any failure to answer permits. */
    public boolean allow(Player player) {
        if (veto == null) {
            return true;
        }
        try {
            return (boolean) veto.invoke(null, player.getUniqueId());
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * Record one side of a completed trade.
     *
     * <p>Called once per player. {@code paid} is what they handed over and {@code received} what
     * came back, so a lopsided trade — a large sum against nothing — is visible as such in the
     * ledger rather than looking like a fair swap.
     *
     * <p>Fire-and-forget: the trade has already committed, and an audit failure must never be
     * allowed to look like a trade failure.
     */
    public void observe(Player player, Player counterparty, double paid, double received,
                        List<ItemStack> given, List<ItemStack> got) {
        if (bridge == null) {
            return;
        }
        double net = received - paid;
        boolean incoming = net >= 0;
        String meta = "gave=" + given.size() + ";got=" + got.size();
        // Coins are the only thing with a price here, so an item-for-item trade reports an amount
        // of zero. Naming the items is what keeps a rare item funnelled to an alt visible in the
        // ledger at all.
        String items = "gave " + TradeLog.describe(given) + "; got " + TradeLog.describe(got);
        if (items.length() > ITEM_LIMIT) {
            // ASCII marker and a cut on a code point boundary: a stray non-Latin character or half a
            // surrogate pair fails the insert on a latin1/utf8mb3 MySQL, which rolls back the whole batch.
            int cut = ITEM_LIMIT - 3;
            if (Character.isHighSurrogate(items.charAt(cut - 1))) {
                cut--;
            }
            items = items.substring(0, cut) + "...";
        }
        try {
            bridge.invoke(null, player.getUniqueId(), player.getName(), SOURCE, "trade",
                    Math.abs(net), incoming, Double.NaN,
                    counterparty.getUniqueId(), counterparty.getName(), items, meta);
        } catch (Throwable ignored) {
        }
    }
}
