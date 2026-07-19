package com.mystipixel.royaltrade.hooks;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Money, via Vault.
 *
 * <p>Resolved once at startup. When no economy is registered the plugin still runs, but every
 * balance check reports zero, so a coin offer can never be confirmed — items still trade fine. That
 * is deliberate: a trade plugin silently treating "no economy" as "everyone can afford anything"
 * would hand out free items.
 */
public final class EconomyHook {

    private final Economy economy;

    public EconomyHook() {
        Economy resolved = null;
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            RegisteredServiceProvider<Economy> rsp =
                    Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                resolved = rsp.getProvider();
            }
        }
        this.economy = resolved;
    }

    public boolean isPresent() {
        return economy != null;
    }

    public double balance(Player player) {
        return economy == null ? 0.0 : economy.getBalance(player);
    }

    public boolean has(Player player, double amount) {
        if (amount <= 0) {
            return true;
        }
        return economy != null && economy.has(player, amount);
    }

    public boolean withdraw(Player player, double amount) {
        if (amount <= 0) {
            return true;
        }
        return economy != null && economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    public boolean deposit(Player player, double amount) {
        if (amount <= 0) {
            return true;
        }
        return economy != null && economy.depositPlayer(player, amount).transactionSuccess();
    }

    public String format(double amount) {
        return economy == null ? String.format("%,.2f", amount) : economy.format(amount);
    }
}
