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

    private MessageManager messages;
    private EconomyHook economy;
    private Escrow escrow;
    private TradeLog log;
    private TradeManager trades;
    private TradeGui gui;
    private SignInput signInput;

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

        int recovered = escrow.load();
        if (recovered > 0) {
            getLogger().warning(recovered + " player(s) had items escrowed when the server last "
                    + "stopped. They will be returned on their next join.");
        }

        trades = new TradeManager(economy, escrow, log, new EconGuardHook());
        gui = new TradeGui(economy);
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
        getLogger().info("RoyalTrade enabled.");
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
