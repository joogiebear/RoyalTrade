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
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Trade window events. Every click is cancelled and then acted on by hand, so no item ever moves by
 * vanilla inventory mechanics (shift-click, hotbar swap, gather, drag).
 */
public final class TradeListener implements Listener {

    private final RoyalTradePlugin plugin;

    // windows we closed on purpose; keyed to the exact window so a flag whose close never came
    // can't swallow the close of a later trade's window
    private final Map<UUID, Inventory> expectedClose = new HashMap<>();
    // players at the coin prompt, so closing the window for it does not cancel the trade
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

        // cancel clicks in the player's own inventory too, or a shift-click bypasses the session
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
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) {
                return;
            }
            switch (plugin.blockedItems().check(clicked)) {
                case ITEM -> {
                    plugin.messages().send(player, "item-blocked");
                    return;
                }
                case CONTENTS -> {
                    plugin.messages().send(player, "item-blocked-inside");
                    return;
                }
                case NONE -> { }
            }
            if (side.offered().size() >= TradeGui.MINE.length) {
                return;
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
            long wait = session.confirmBlockedFor(System.currentTimeMillis(), plugin.confirmDelayMillis());
            if (wait > 0) {
                plugin.messages().send(player, "confirm-wait",
                        Map.of("seconds", String.valueOf((wait + 999) / 1000)));
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
                // inventory before escrow when an item comes out: a crash duplicates, never loses
                plugin.trades().saveInventory(player);
                afterChange(session, player, hadConfirmation);
            }
        }
    }

    private static boolean anyConfirmed(TradeSession session) {
        return session.a().confirmed() || session.b().confirmed();
    }

    // only announce a cleared confirmation when there was one, so players don't learn to ignore it
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

    /** Closing the window ends the trade, except for closes we caused or the coin prompt. */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        UUID id = player.getUniqueId();
        Inventory expected = expectedClose.remove(id);
        if ((expected != null && expected.equals(event.getInventory())) || awaitingCoins.contains(id)) {
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
        expectedClose.remove(event.getPlayer().getUniqueId());
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

    // a dialog, not chat, keeps the amount private until it is offered; opening it closes the trade
    // window, so awaitingCoins suppresses close-cancels-trade until they come back
    private void promptCoins(Player player, TradeSession session) {
        if (!plugin.economy().isPresent()) {
            plugin.messages().send(player, "no-economy");
            return;
        }
        awaitingCoins.add(player.getUniqueId());
        Inventory view = plugin.gui().viewOf(player);
        if (view != null) {
            expectedClose.put(player.getUniqueId(), view);
        }
        plugin.textInput().request(player,
                List.of(plugin.messages().raw("coins-prompt-title"), plugin.messages().raw("coins-prompt")),
                typed -> applyCoins(player, typed));
    }

    // typed is null when no answer is coming
    private void applyCoins(Player player, String typed) {
        if (typed == null && plugin.textInput().isWaiting(player)) {
            return;                      // a newer coin prompt is up and still needs awaitingCoins
        }
        awaitingCoins.remove(player.getUniqueId());
        TradeSession session = plugin.trades().sessionOf(player);
        if (session == null) {
            return;
        }
        if (typed == null && !screenFree(player)) {
            // dead or displaced by another menu: that counts as leaving the trade window, so cancel
            cancel(session, player, true);
            return;
        }
        if (!session.editable()) {
            // both confirmed while this player was at the prompt; reopen so they can watch it settle
            if (session.state() == TradeSession.State.SETTLING) {
                reopen(player, session);
            }
            return;
        }
        TradeSession.Side side = session.sideOf(player.getUniqueId());
        if (side == null) {
            return;
        }
        if (typed == null || typed.isBlank() || typed.equalsIgnoreCase("cancel")) {
            reopen(player, session);
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(typed.replace(",", "").replace("$", ""));
        } catch (NumberFormatException e) {
            plugin.messages().send(player, "coins-invalid");
            reopen(player, session);
            return;
        }
        // parseDouble accepts "NaN" and "Infinity", and NaN passes every < check
        if (!Double.isFinite(amount) || amount < 0) {
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
        reopen(player, session);
    }

    private static boolean screenFree(Player player) {
        if (!player.isOnline() || player.isDead()) {
            return false;
        }
        InventoryType top = player.getOpenInventory().getTopInventory().getType();
        return top == InventoryType.CRAFTING || top == InventoryType.CREATIVE;
    }

    private void reopen(Player player, TradeSession session) {
        plugin.gui().render(session);
        if (plugin.gui().viewOf(player) != null) {
            player.openInventory(plugin.gui().viewOf(player));
        }
    }

    private void cancel(TradeSession session, Player actor, boolean tellActor) {
        if (session.state() == TradeSession.State.RECOVERY) {
            if (tellActor) plugin.messages().send(actor, "payment-recovery");
            return;
        }
        Player a = Bukkit.getPlayer(session.a().playerId());
        Player b = Bukkit.getPlayer(session.b().playerId());
        plugin.trades().cancel(session);

        for (Player p : new Player[]{a, b}) {
            if (p == null) {
                continue;
            }
            // only close the trade window itself; flagging a close that never comes would swallow
            // the next trade window's real close
            Inventory top = p.getOpenInventory().getTopInventory();
            if (plugin.gui().isTradeView(p, top)) {
                expectedClose.put(p.getUniqueId(), top);
                p.closeInventory();
            }
            plugin.gui().forget(p);
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
