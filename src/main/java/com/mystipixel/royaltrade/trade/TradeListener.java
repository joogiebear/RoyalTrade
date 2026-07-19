package com.mystipixel.royaltrade.trade;

import com.mystipixel.royaltrade.RoyalTradePlugin;
import com.mystipixel.royaltrade.gui.TradeGui;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the trade window responds to.
 *
 * <p>Every click inside the window is cancelled and then acted on by hand. Nothing is ever allowed
 * to move by vanilla inventory mechanics: shift-click, hotbar swap, double-click gather and drag all
 * move items in ways that are easy to reason about wrongly, and one missed case in a trade window is
 * a duplication bug. Cancelling first and moving items ourselves means the only item movements that
 * exist are the ones written here.
 */
public final class TradeListener implements Listener {

    private final RoyalTradePlugin plugin;

    /** Players whose window we closed on purpose, so the close handler does not cancel the trade. */
    private final Set<UUID> expectedClose = new HashSet<>();
    /** Players who are typing a coin amount in chat. */
    private final Set<UUID> awaitingCoins = new HashSet<>();

    public TradeListener(RoyalTradePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        TradeSession session = plugin.trades().sessionOf(player);
        if (session == null) {
            return;
        }
        boolean top = event.getClickedInventory() != null
                && event.getClickedInventory().equals(event.getView().getTopInventory());
        if (!plugin.gui().isTradeView(player, event.getView().getTopInventory())) {
            return;
        }

        // Cancel unconditionally, including clicks in the player's own inventory: a shift-click from
        // below would otherwise drop an item into the window without going through the session.
        event.setCancelled(true);

        TradeSession.Side side = session.sideOf(player.getUniqueId());
        if (side == null) {
            return;
        }
        if (!session.editable()) {
            if (event.getSlot() != TradeGui.CLOSE || !top) {
                plugin.messages().send(player, "locked");
                return;
            }
        }

        if (top) {
            handleTopClick(event, player, session, side);
        } else {
            // From their own inventory: offer the clicked stack.
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) {
                return;
            }
            if (side.offered().size() >= TradeGui.MINE.length) {
                return;                                   // window full; nothing to do
            }
            boolean hadConfirmation = anyConfirmed(session);
            if (session.addItem(side, clicked)) {
                event.getClickedInventory().setItem(event.getSlot(), null);
                afterChange(session, player, hadConfirmation);
            }
        }
    }

    private void handleTopClick(InventoryClickEvent event, Player player,
                                TradeSession session, TradeSession.Side side) {
        int slot = event.getSlot();

        if (slot == TradeGui.CLOSE) {
            cancel(session, player, true);
            return;
        }
        if (slot == TradeGui.MY_CONFIRM) {
            if (side.confirmed()) {
                session.unconfirm(side);
                plugin.gui().render(session);
                return;
            }
            if (!session.confirm(side)) {
                plugin.messages().send(player, "nothing-offered");
                return;
            }
            if (session.a().confirmed() && session.b().confirmed()) {
                session.beginSettling(System.currentTimeMillis());
            }
            plugin.gui().render(session);
            return;
        }
        if (slot == TradeGui.MY_COINS) {
            promptCoins(player, session);
            return;
        }
        int index = TradeGui.mineIndex(slot);
        if (index >= 0) {
            boolean hadConfirmation = anyConfirmed(session);
            ItemStack removed = session.removeItem(side, index);
            if (removed != null) {
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(removed);
                for (ItemStack stack : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), stack);
                }
                afterChange(session, player, hadConfirmation);
            }
        }
        // Slots on their side, the dividers and the status items are read-only: already cancelled.
    }

    private static boolean anyConfirmed(TradeSession session) {
        return session.a().confirmed() || session.b().confirmed();
    }

    /**
     * Persist the new escrow and redraw both windows.
     *
     * <p>The other player is told their confirmation was cleared only when there was one to clear.
     * Announcing it on every click would train them to ignore the line, and that line is the whole
     * point — it is what tells someone the deal they agreed to is no longer the deal on the table.
     */
    private void afterChange(TradeSession session, Player actor, boolean hadConfirmation) {
        plugin.trades().persist(session);
        plugin.gui().render(session);
        if (!hadConfirmation) {
            return;
        }
        Player other = Bukkit.getPlayer(session.other(session.sideOf(actor.getUniqueId())).playerId());
        if (other != null) {
            plugin.messages().send(other, "confirm-reset");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (plugin.gui().isTradeView(player, event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    /**
     * Closing the window ends the trade.
     *
     * <p>The exceptions are a close we performed ourselves and a close caused by the coin prompt —
     * without those, completing a trade or typing an amount would immediately cancel it.
     */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        UUID id = player.getUniqueId();
        if (expectedClose.remove(id) || awaitingCoins.contains(id)) {
            return;
        }
        TradeSession session = plugin.trades().sessionOf(player);
        if (session != null && !session.finished()) {
            cancel(session, player, true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        TradeSession session = plugin.trades().sessionOf(event.getPlayer());
        awaitingCoins.remove(event.getPlayer().getUniqueId());
        plugin.gui().forget(event.getPlayer());
        if (session != null && !session.finished()) {
            cancel(session, event.getPlayer(), false);
        }
    }

    /** Hand back anything an unclean shutdown left owed to this player. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (plugin.escrow().pendingFor(player.getUniqueId()).isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            int delivered = plugin.escrow().deliver(player);
            if (delivered > 0) {
                plugin.messages().send(player, "returned-items");
            }
            if (!plugin.escrow().pendingFor(player.getUniqueId()).isEmpty()) {
                plugin.messages().send(player, "returned-partial");
            }
        }, 20L);
    }

    // ------------------------------------------------------------------ coins

    private void promptCoins(Player player, TradeSession session) {
        if (!plugin.economy().isPresent()) {
            plugin.messages().send(player, "no-economy");
            return;
        }
        awaitingCoins.add(player.getUniqueId());
        expectedClose.add(player.getUniqueId());
        player.closeInventory();
        plugin.messages().send(player, "coins-prompt");
    }

    /**
     * Read a coin amount typed in chat.
     *
     * <p>Chat is async, so nothing here touches the session directly — the work is handed to the
     * main thread. Mutating a trade from an async thread is the kind of race that produces two
     * different answers to "what was on the table".
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!awaitingCoins.contains(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        String typed = event.getMessage().trim();
        awaitingCoins.remove(player.getUniqueId());

        Bukkit.getScheduler().runTask(plugin, () -> {
            TradeSession session = plugin.trades().sessionOf(player);
            if (session == null || !session.editable()) {
                return;
            }
            TradeSession.Side side = session.sideOf(player.getUniqueId());
            if (side == null) {
                return;
            }
            if (!typed.equalsIgnoreCase("cancel")) {
                double amount;
                try {
                    amount = Double.parseDouble(typed.replace(",", ""));
                } catch (NumberFormatException e) {
                    plugin.messages().send(player, "coins-invalid");
                    reopen(player, session);
                    return;
                }
                if (amount < 0) {
                    plugin.messages().send(player, "coins-invalid");
                    reopen(player, session);
                    return;
                }
                if (!plugin.economy().has(player, amount)) {
                    plugin.messages().send(player, "coins-too-much");
                    reopen(player, session);
                    return;
                }
                boolean hadConfirmation = anyConfirmed(session);
                session.setCoins(side, amount);
                plugin.trades().persist(session);
                plugin.messages().send(player, "coins-set",
                        Map.of("amount", plugin.economy().format(amount)));
                Player other = Bukkit.getPlayer(session.other(side).playerId());
                if (other != null && hadConfirmation) {
                    plugin.messages().send(other, "confirm-reset");
                }
            }
            reopen(player, session);
        });
    }

    private void reopen(Player player, TradeSession session) {
        plugin.gui().render(session);
        if (plugin.gui().viewOf(player) != null) {
            player.openInventory(plugin.gui().viewOf(player));
        }
    }

    // ------------------------------------------------------------------ cancel

    private void cancel(TradeSession session, Player actor, boolean tellActor) {
        Player a = Bukkit.getPlayer(session.a().playerId());
        Player b = Bukkit.getPlayer(session.b().playerId());
        plugin.trades().cancel(session);

        for (Player p : new Player[]{a, b}) {
            if (p == null) {
                continue;
            }
            plugin.gui().forget(p);
            if (p.getOpenInventory() != null) {
                expectedClose.add(p.getUniqueId());
                p.closeInventory();
            }
            if (p.equals(actor)) {
                if (tellActor) {
                    plugin.messages().send(p, "cancelled");
                }
            } else {
                plugin.messages().send(p, "cancelled-by-other",
                        Map.of("player", actor.getName()));
            }
        }
    }
}
