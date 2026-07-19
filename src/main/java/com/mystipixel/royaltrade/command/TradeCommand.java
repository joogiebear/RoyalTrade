package com.mystipixel.royaltrade.command;

import com.mystipixel.royaltrade.RoyalTradePlugin;
import com.mystipixel.royaltrade.trade.TradeSession;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /trade <player>} — sends a request, or accepts one already waiting from that player.
 *
 * <p>One command does both halves on purpose: the second player types the same thing the first one
 * did, so there is no "accept" command to remember, and no window where a stray /accept lands on a
 * trade the player did not mean to join.
 */
public final class TradeCommand implements CommandExecutor, TabCompleter {

    private final RoyalTradePlugin plugin;
    private final Map<UUID, Long> lastRequest = new HashMap<>();

    public TradeCommand(RoyalTradePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")
                && sender.hasPermission("royaltrade.admin")) {
            plugin.reload();
            plugin.messages().send(sender, "reloaded");
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (args.length != 1) {
            plugin.messages().send(player, "usage");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            plugin.messages().send(player, "no-such-player");
            return true;
        }
        if (target.equals(player)) {
            plugin.messages().send(player, "cannot-trade-self");
            return true;
        }
        if (plugin.trades().inTrade(player)) {
            plugin.messages().send(player, "already-trading");
            return true;
        }
        if (plugin.trades().inTrade(target)) {
            plugin.messages().send(player, "they-are-trading",
                    Map.of("player", target.getName()));
            return true;
        }
        if (!withinReach(player, target)) {
            return true;
        }
        if (tooNew(player, player, "too-new") || tooNew(player, target, "they-are-too-new")) {
            return true;
        }

        // Already invited by this player? Then this is the acceptance.
        UUID pending = plugin.trades().pendingRequest(player, plugin.requestExpiryMillis());
        if (pending != null && pending.equals(target.getUniqueId())) {
            plugin.trades().clearRequest(player);
            TradeSession session = plugin.trades().open(player, target);
            plugin.gui().open(session, player, target);
            plugin.messages().send(player, "opened", Map.of("player", target.getName()));
            plugin.messages().send(target, "opened", Map.of("player", player.getName()));
            return true;
        }

        long now = System.currentTimeMillis();
        Long last = lastRequest.get(player.getUniqueId());
        if (last != null && now - last < plugin.requestCooldownMillis()) {
            plugin.messages().send(player, "request-cooldown");
            return true;
        }
        lastRequest.put(player.getUniqueId(), now);

        plugin.trades().request(player, target);
        plugin.messages().send(player, "request-sent", Map.of("player", target.getName()));
        plugin.messages().send(target, "request-received", Map.of("player", player.getName()));
        return true;
    }

    /** Distance and world limits. Both are anti-RMT levers as much as convenience ones. */
    private boolean withinReach(Player player, Player target) {
        if (plugin.sameWorldOnly() && !player.getWorld().equals(target.getWorld())) {
            plugin.messages().send(player, "different-world", Map.of("player", target.getName()));
            return false;
        }
        double max = plugin.maxDistance();
        if (max > 0 && player.getWorld().equals(target.getWorld())
                && player.getLocation().distance(target.getLocation()) > max) {
            plugin.messages().send(player, "too-far", Map.of("player", target.getName()));
            return false;
        }
        return true;
    }

    /**
     * Refuse trades from accounts too new to have earned anything.
     *
     * <p>Off by default. It is a blunt instrument, but a throwaway alt created to receive a
     * character's wealth is the shape most RMT takes, and a playtime floor is the cheapest thing
     * that makes it inconvenient.
     */
    private boolean tooNew(Player toTell, Player subject, String key) {
        double hours = plugin.minPlaytimeHours();
        if (hours <= 0) {
            return false;
        }
        int ticks = subject.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE);
        double played = ticks / 20.0 / 3600.0;
        if (played >= hours) {
            return false;
        }
        plugin.messages().send(toTell, key,
                Map.of("player", subject.getName(), "hours", String.format("%.0f", hours)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1 && sender instanceof Player player) {
            String prefix = args[0].toLowerCase();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(player) && online.getName().toLowerCase().startsWith(prefix)) {
                    out.add(online.getName());
                }
            }
        }
        return out;
    }
}
