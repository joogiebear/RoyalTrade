package com.mystipixel.royaltrade;

import com.mystipixel.royaltrade.command.TradeCommand;
import com.mystipixel.royaltrade.data.Escrow;
import com.mystipixel.royaltrade.data.TradeLog;
import com.mystipixel.royaltrade.gui.TextInput;
import com.mystipixel.royaltrade.gui.TradeGui;
import com.mystipixel.royaltrade.gui.TradeMenu;
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
 * Player-to-player trading. See {@link TradeSession} for the rules and {@link TradeManager#commit}
 * for the transfer.
 */
public final class RoyalTradePlugin extends JavaPlugin {

    // names this plugin's bStats project, so it is fixed rather than configurable
    private static final int BSTATS_PLUGIN_ID = 33890;

    private MessageManager messages;
    private EconomyHook economy;
    private EconGuardHook econGuard;
    private Escrow escrow;
    private TradeLog log;
    private TradeManager trades;
    private TradeGui gui;
    private TextInput textInput;

    private com.mystipixel.royaltrade.data.TradeToggles toggles;
    private com.mystipixel.royaltrade.config.BlockedItems blockedItems;

    private long settleMillis;
    private long confirmDelayMillis;
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
        escrow = new Escrow(getDataFolder(), getLogger(), task -> {
            if (isEnabled()) {
                getServer().getScheduler().runTask(this, task);
            }
        });
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
        gui = new TradeGui(economy, this::settleMillis, this::confirmDelayMillis,
                TradeMenu.load(getDataFolder(), getLogger()));
        textInput = new TextInput(this, messages);

        TradeCommand command = new TradeCommand(this);
        if (getCommand("trade") != null) {
            getCommand("trade").setExecutor(command);
            getCommand("trade").setTabCompleter(command);
        }
        getServer().getPluginManager().registerEvents(command, this);
        getServer().getPluginManager().registerEvents(new TradeListener(this), this);
        getServer().getPluginManager().registerEvents(textInput, this);

        // once a second is enough: the settle window is measured in seconds
        getServer().getScheduler().runTaskTimer(this, this::tickSettling, 20L, 20L);

        if (!economy.isPresent()) {
            getLogger().warning("No Vault economy found: items can be traded, coins cannot.");
        }
        setupMetrics();
        getLogger().info("RoyalTrade enabled.");
    }

    // owners opt out globally in plugins/bStats/config.yml
    private void setupMetrics() {
        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("economy", () -> String.valueOf(economy.isPresent())));
        metrics.addCustomChart(new SimplePie("econguard", () -> String.valueOf(econGuard.isPresent())));
        metrics.addCustomChart(new SimplePie("settle_seconds",
                () -> String.valueOf(settleMillis / 1000L)));
        metrics.addCustomChart(new SimplePie("distance_limited",
                () -> String.valueOf(maxDistance > 0.0)));
    }

    @Override
    public void onDisable() {
        if (textInput != null) {
            textInput.shutdown();
        }
        // cancel open trades so escrow is returned or queued, not left only in memory
        for (TradeSession session : trades == null ? java.util.List.<TradeSession>of() : trades.active()) {
            trades.cancel(session);
        }
        if (escrow != null) {
            escrow.close();
        }
    }

    private void readConfig() {
        blockedItems = com.mystipixel.royaltrade.config.BlockedItems.parse(
                getConfig().getStringList("blocked-items"), getLogger());
        settleMillis = Math.max(0L, getConfig().getLong("settle-seconds", 3L) * 1000L);
        // floored at one second: it stops a confirm sent against the old offer landing on the new one
        confirmDelayMillis = Math.max(1L, getConfig().getLong("confirm-delay-seconds", 2L)) * 1000L;
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
        gui.setMenu(TradeMenu.load(getDataFolder(), getLogger()));
    }

    // completes trades whose settle window has elapsed; TradeSession refuses edits while settling
    private void tickSettling() {
        long now = System.currentTimeMillis();
        for (TradeSession session : trades.active()) {
            if (session.state() == TradeSession.State.ACTIVE
                    && now - session.changedAt() < confirmDelayMillis + 1000L) {
                gui.render(session);             // count the confirm delay down, then clear it
                continue;
            }
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
            // a failed commit goes back to the players so they can fix it and confirm again
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
                case RESTRICTED -> "trade-restricted";
                default -> "cancelled";
            };
            // a restriction won't clear by confirming again, so it ends the trade like a departure
            if (failure == TradeManager.Failure.OFFLINE || failure == TradeManager.Failure.RESTRICTED) {
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

    public EconGuardHook econGuard() {
        return econGuard;
    }

    public TradeGui gui() {
        return gui;
    }

    public TextInput textInput() {
        return textInput;
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

    public long confirmDelayMillis() {
        return confirmDelayMillis;
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
