package com.mystipixel.royaltrade;

import com.mystipixel.royaltrade.command.TradeCommand;
import com.mystipixel.royaltrade.data.Escrow;
import com.mystipixel.royaltrade.data.TradeLog;
import com.mystipixel.royaltrade.gui.SignInput;
import com.mystipixel.royaltrade.gui.TradeGui;
import com.mystipixel.royaltrade.hooks.EconGuardHook;
import com.mystipixel.royaltrade.hooks.EconomyHook;
import com.mystipixel.royaltrade.message.MessageManager;
import com.mystipixel.royaltrade.trade.TradeListener;
import com.mystipixel.royaltrade.trade.TradeManager;
import com.mystipixel.royaltrade.trade.TradeSession;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Player-to-player trading, built so that agreeing to a deal and then changing it is impossible.
 *
 * <p>See {@link TradeSession} for the rules and {@link TradeManager#commit} for the transfer. The
 * short version: any change clears both confirmations, a settle window freezes the trade once both
 * sides agree, and offered items are held in escrow that survives a crash.
 */
public final class RoyalTradePlugin extends JavaPlugin {

    /** bStats project id. Identifies the plugin, not the server, so it is fixed rather than configurable. */
    private static final int BSTATS_PLUGIN_ID = 33890;

    private MessageManager messages;
    private EconomyHook economy;
    private EconGuardHook econGuard;
    private Escrow escrow;
    private TradeLog log;
    private TradeManager trades;
    private TradeGui gui;
    private SignInput signInput;

    private com.mystipixel.royaltrade.data.TradeToggles toggles;
    private com.mystipixel.royaltrade.config.BlockedItems blockedItems;

    private long settleMillis;
    private long requestExpiryMillis;
    private long requestCooldownMillis;
    private double maxDistance;
    private boolean sameWorldOnly;
    private double minPlaytimeHours;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();

        messages = new MessageManager(getDataFolder(), getLogger());
        economy = new EconomyHook();
        escrow = new Escrow(getDataFolder(), getLogger());
        log = new TradeLog(getDataFolder(), getLogger());
        toggles = new com.mystipixel.royaltrade.data.TradeToggles(getDataFolder(), getLogger());

        com.mystipixel.royaltrade.data.PaymentJournal payments;
        int recovered;
        try {
            payments = new com.mystipixel.royaltrade.data.PaymentJournal(getDataFolder().toPath().resolve("payments"));
            java.util.Set<java.util.UUID> heldSessions = new java.util.HashSet<>();
            for (var record : payments.pending().values()) {
                String reason = record.getProperty("reason");
                if (!reason.startsWith("trade:")) throw new IllegalStateException("Invalid trade receipt reason");
                heldSessions.add(java.util.UUID.fromString(reason.substring("trade:".length())));
            }
            recovered = escrow.load(heldSessions);
            if (!heldSessions.isEmpty()) getLogger().severe(heldSessions.size()
                    + " trade(s) held for payment reconciliation. See payments/pending and docs/payment-recovery.md.");
        } catch (RuntimeException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Cannot load payment/escrow state safely; disabling", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (recovered > 0) {
            getLogger().warning(recovered + " player(s) had items escrowed when the server last "
                    + "stopped. They will be returned on their next join.");
        }

        econGuard = new EconGuardHook();
        trades = new TradeManager(economy, escrow, log, econGuard, payments, getLogger());
        gui = new TradeGui(economy, this::settleMillis);
        signInput = new SignInput(this);

        TradeCommand command = new TradeCommand(this);
        if (getCommand("trade") != null) {
            getCommand("trade").setExecutor(command);
            getCommand("trade").setTabCompleter(command);
        }
        getServer().getPluginManager().registerEvents(new TradeListener(this), this);
        getServer().getPluginManager().registerEvents(signInput, this);

        // Drive the settle window. One second is fine: the window is measured in seconds, and a
        // shorter tick would only add wake-ups for a queue that is almost always empty.
        getServer().getScheduler().runTaskTimer(this, this::tickSettling, 20L, 20L);

        if (!economy.isPresent()) {
            getLogger().warning("No Vault economy found — items can be traded, coins cannot.");
        }
        setupMetrics();
        getLogger().info("RoyalTrade enabled.");
    }

    /**
     * Anonymous usage reporting via bStats.
     *
     * <p>Server owners who want no reporting disable it globally in plugins/bStats/config.yml, which
     * is the mechanism bStats provides; the id itself is fixed because it names this plugin's project.
     */
    private void setupMetrics() {
        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        // Trades without a Vault economy are items-only, which is a different plugin in practice.
        metrics.addCustomChart(new SimplePie("economy", () -> String.valueOf(economy.isPresent())));
        metrics.addCustomChart(new SimplePie("econguard", () -> String.valueOf(econGuard.isPresent())));
        metrics.addCustomChart(new SimplePie("settle_seconds",
                () -> String.valueOf(settleMillis / 1000L)));
        metrics.addCustomChart(new SimplePie("distance_limited",
                () -> String.valueOf(maxDistance > 0.0)));
    }

    @Override
    public void onDisable() {
        // Cancel every open trade rather than let the shutdown strand escrowed items. Cancelling
        // returns them to inventories where possible and queues the rest, so nothing is left only in
        // memory when the process exits.
        for (TradeSession session : trades == null ? java.util.List.<TradeSession>of() : trades.active()) {
            trades.cancel(session);
        }
    }

    private void readConfig() {
        blockedItems = com.mystipixel.royaltrade.config.BlockedItems.parse(
                getConfig().getStringList("blocked-items"), getLogger());
        settleMillis = Math.max(0L, getConfig().getLong("settle-seconds", 3L) * 1000L);
        requestExpiryMillis = Math.max(1L, getConfig().getLong("request-expiry-seconds", 60L)) * 1000L;
        requestCooldownMillis = Math.max(0L, getConfig().getLong("request-cooldown-seconds", 5L)) * 1000L;
        maxDistance = getConfig().getDouble("max-distance", -1.0);
        sameWorldOnly = getConfig().getBoolean("same-world-only", true);
        minPlaytimeHours = getConfig().getDouble("min-playtime-hours", 0.0);
    }

    public void reload() {
        reloadConfig();
        readConfig();
        messages.reload();
    }

    /**
     * Complete any trade whose settle window has elapsed.
     *
     * <p>The window exists so that an edit landing in the same tick as a confirmation cannot ride
     * into the commit. Nothing here can change the offer — {@link TradeSession} refuses edits while
     * settling — so this only decides when to pull the trigger.
     */
    private void tickSettling() {
        long now = System.currentTimeMillis();
        for (TradeSession session : trades.active()) {
            if (session.state() != TradeSession.State.SETTLING) {
                continue;
            }
            if (now - session.settlingSince() < settleMillis) {
                gui.render(session);             // tick the countdown item once a second
                continue;
            }
            Player a = Bukkit.getPlayer(session.a().playerId());
            Player b = Bukkit.getPlayer(session.b().playerId());
            TradeManager.Failure failure = trades.commit(session);
            if (failure == TradeManager.Failure.NONE) {
                closeBoth(a, b);
                if (a != null) {
                    messages.send(a, "completed");
                }
                if (b != null) {
                    messages.send(b, "completed");
                }
                continue;
            }
            // Anything that stops a commit puts the trade back in players' hands rather than
            // silently dropping it — they can fix the problem and confirm again.
            if (failure == TradeManager.Failure.RECOVERY_REQUIRED) {
                closeBoth(a, b);
                if (a != null) messages.send(a, "payment-recovery");
                if (b != null) messages.send(b, "payment-recovery");
                continue;
            }
            session.abortSettling();
            String key = switch (failure) {
                case INSUFFICIENT_FUNDS -> "not-enough-money";
                case NO_INVENTORY_SPACE -> "full-inventory";
                case ECONOMY_ERROR -> "economy-error";
                case OFFLINE -> "other-left";
                default -> "cancelled";
            };
            if (failure == TradeManager.Failure.OFFLINE) {
                trades.cancel(session);
                closeBoth(a, b);
            } else {
                gui.render(session);
            }
            if (a != null) {
                messages.send(a, key);
            }
            if (b != null) {
                messages.send(b, key);
            }
        }
    }

    private void closeBoth(Player a, Player b) {
        if (a != null) {
            gui.forget(a);
            a.closeInventory();
        }
        if (b != null) {
            gui.forget(b);
            b.closeInventory();
        }
    }

    // ------------------------------------------------------------------ accessors

    public MessageManager messages() {
        return messages;
    }

    public EconomyHook economy() {
        return economy;
    }

    public Escrow escrow() {
        return escrow;
    }

    public TradeManager trades() {
        return trades;
    }

    public TradeGui gui() {
        return gui;
    }

    public SignInput signInput() {
        return signInput;
    }

    public com.mystipixel.royaltrade.data.TradeToggles toggles() {
        return toggles;
    }

    public com.mystipixel.royaltrade.config.BlockedItems blockedItems() {
        return blockedItems;
    }

    public long settleMillis() {
        return settleMillis;
    }

    public long requestExpiryMillis() {
        return requestExpiryMillis;
    }

    public long requestCooldownMillis() {
        return requestCooldownMillis;
    }

    public double maxDistance() {
        return maxDistance;
    }

    public boolean sameWorldOnly() {
        return sameWorldOnly;
    }

    public double minPlaytimeHours() {
        return minPlaytimeHours;
    }
}
