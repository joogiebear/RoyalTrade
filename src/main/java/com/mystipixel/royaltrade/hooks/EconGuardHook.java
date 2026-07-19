package com.mystipixel.royaltrade.hooks;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
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
 */
public final class EconGuardHook {

    private static final String SOURCE = "trade";

    private final Method bridge;

    public EconGuardHook() {
        Method resolved = null;
        if (Bukkit.getPluginManager().isPluginEnabled("EconGuard")) {
            try {
                Class<?> econGuard = Class.forName("com.mystipixel.econguard.api.EconGuard");
                resolved = econGuard.getMethod("record",
                        UUID.class, String.class, String.class, String.class,
                        double.class, boolean.class, double.class,
                        UUID.class, String.class, String.class, String.class);
            } catch (Throwable ignored) {
                // EconGuard absent, or older than the bridge — stay a no-op.
            }
        }
        this.bridge = resolved;
    }

    public boolean isPresent() {
        return bridge != null;
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
    public void observe(Player player, Player counterparty,
                        double paid, double received, int itemsGiven, int itemsReceived) {
        if (bridge == null) {
            return;
        }
        double net = received - paid;
        boolean incoming = net >= 0;
        String meta = "gave=" + itemsGiven + ";got=" + itemsReceived;
        try {
            bridge.invoke(null, player.getUniqueId(), player.getName(), SOURCE, "trade",
                    Math.abs(net), incoming, Double.NaN,
                    counterparty.getUniqueId(), counterparty.getName(), null, meta);
        } catch (Throwable ignored) {
        }
    }
}
