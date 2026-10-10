package com.mystipixel.royaltrade.hooks;

import com.mystipixel.royaltrade.data.TradeLog;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * Reports completed trades to EconGuard and consults its {@code allowTrade} veto. Wired by
 * reflection against EconGuard's {@code EconGuard.record(...)} bridge, so there is no build-time
 * dependency. If the veto is missing or fails, trading is permitted.
 */
public final class EconGuardHook {

    private static final String SOURCE = "trade";

    // the ledger's item column is VARCHAR(255) on MySQL
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
                    // predates the veto bridge: reporting still works, allow() permits
                }
            } catch (Throwable ignored) {
                // EconGuard absent or older than the bridge: stay a no-op
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
     * Record one side of a completed trade; called once per player. {@code paid} is what they handed
     * over and {@code received} what came back. Fire-and-forget: failures are ignored.
     */
    public void observe(Player player, Player counterparty, double paid, double received,
                        List<ItemStack> given, List<ItemStack> got) {
        if (bridge == null) {
            return;
        }
        double net = received - paid;
        boolean incoming = net >= 0;
        String meta = "gave=" + given.size() + ";got=" + got.size();
        // item-for-item trades report zero coins, so the items are named to keep them visible
        String items = "gave " + TradeLog.describe(given) + "; got " + TradeLog.describe(got);
        if (items.length() > ITEM_LIMIT) {
            // ASCII marker and a cut on a code point boundary: half a surrogate pair fails the insert
            // on a latin1/utf8mb3 MySQL
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
