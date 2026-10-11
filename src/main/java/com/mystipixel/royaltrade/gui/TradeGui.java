package com.mystipixel.royaltrade.gui;

import com.mystipixel.royaltrade.hooks.EconomyHook;
import com.mystipixel.royaltrade.trade.TradeSession;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The trade window. Each player gets their own inventory so "your offer" is always on the left;
 * {@link #render} redraws both after every change so they cannot disagree.
 */
public final class TradeGui {

    // a window keeps the layout it opened with, so a reload can't hide offered items mid-trade
    private record View(Inventory inventory, TradeMenu menu) {
    }

    private final EconomyHook economy;
    private final LongSupplier settleMillis;
    private final LongSupplier confirmDelayMillis;
    private final Map<UUID, View> views = new HashMap<>();
    private TradeMenu menu;

    public TradeGui(EconomyHook economy, LongSupplier settleMillis, LongSupplier confirmDelayMillis,
                    TradeMenu menu) {
        this.economy = economy;
        this.settleMillis = settleMillis;
        this.confirmDelayMillis = confirmDelayMillis;
        this.menu = menu;
    }

    /** The layout for trades opened from now on. */
    public void setMenu(TradeMenu menu) {
        this.menu = menu;
    }

    public Inventory viewOf(Player player) {
        View view = views.get(player.getUniqueId());
        return view == null ? null : view.inventory();
    }

    public boolean isTradeView(Player player, Inventory inventory) {
        Inventory mine = viewOf(player);
        return mine != null && mine.equals(inventory);
    }

    /** The button at a slot of this player's window, or null. */
    public String buttonAt(Player player, int slot) {
        View view = views.get(player.getUniqueId());
        return view == null ? null : view.menu().buttonAt(slot);
    }

    /** Which index in the player's offer a slot of their window shows, or -1. */
    public int offerIndex(Player player, int slot) {
        View view = views.get(player.getUniqueId());
        return view == null ? -1 : view.menu().myOfferIndex(slot);
    }

    public int offerCapacity(Player player) {
        View view = views.get(player.getUniqueId());
        return view == null ? 0 : view.menu().offerCapacity();
    }

    public void open(TradeSession session, Player a, Player b) {
        TradeMenu layout = menu;
        views.put(a.getUniqueId(), new View(Bukkit.createInventory(null, layout.size(),
                layout.title(Map.of("player", b.getName()))), layout));
        views.put(b.getUniqueId(), new View(Bukkit.createInventory(null, layout.size(),
                layout.title(Map.of("player", a.getName()))), layout));
        render(session);
        a.openInventory(viewOf(a));
        b.openInventory(viewOf(b));
    }

    public void forget(Player player) {
        views.remove(player.getUniqueId());
    }

    /** Redraw both windows from the session. The only way either view changes. */
    public void render(TradeSession session) {
        drawFor(session, session.a());
        drawFor(session, session.b());
    }

    private void drawFor(TradeSession session, TradeSession.Side viewer) {
        View view = views.get(viewer.playerId());
        if (view == null) {
            return;
        }
        Inventory inv = view.inventory();
        TradeMenu layout = view.menu();
        TradeSession.Side them = session.other(viewer);
        inv.clear();

        for (int i = 0; i < inv.getSize(); i++) {
            TradeMenu.Look filler = layout.maskAt(i);
            inv.setItem(i, filler == null ? null : item(filler.fill(Map.of())));
        }
        fill(inv, layout.myOffer(), viewer.offered());
        fill(inv, layout.theirOffer(), them.offered());

        place(inv, layout, TradeMenu.MY_COINS, coinState(viewer.coins()),
                Map.of("amount", economy.format(viewer.coins())));
        place(inv, layout, TradeMenu.THEIR_COINS, coinState(them.coins()),
                Map.of("amount", economy.format(them.coins())));

        boolean settling = session.state() == TradeSession.State.SETTLING;
        long secondsLeft = 0;
        if (settling) {
            long elapsed = System.currentTimeMillis() - session.settlingSince();
            secondsLeft = Math.max(1, (settleMillis.getAsLong() - elapsed + 999) / 1000);
        }
        long blocked = session.confirmBlockedFor(System.currentTimeMillis(), confirmDelayMillis.getAsLong());
        // live countdowns, re-rendered each second; a static label reads as a hang
        Map<String, String> countdown = Map.of("seconds", String.valueOf(secondsLeft));
        if (settling) {
            place(inv, layout, TradeMenu.MY_CONFIRM, "settling", countdown);
        } else if (blocked > 0) {
            place(inv, layout, TradeMenu.MY_CONFIRM, "waiting",
                    Map.of("seconds", String.valueOf((blocked + 999) / 1000)));
        } else {
            place(inv, layout, TradeMenu.MY_CONFIRM, viewer.confirmed() ? "confirmed" : "unconfirmed", Map.of());
        }
        place(inv, layout, TradeMenu.THEIR_CONFIRM,
                settling ? "settling" : them.confirmed() ? "confirmed" : "unconfirmed", countdown);
        place(inv, layout, TradeMenu.CANCEL, TradeMenu.PLAIN, Map.of());
    }

    private static String coinState(double amount) {
        return amount > 0 ? "some" : "none";
    }

    private static void fill(Inventory inv, int[] slots, List<ItemStack> offered) {
        for (int slot : slots) {
            if (slot < inv.getSize()) {
                inv.setItem(slot, null);
            }
        }
        for (int i = 0; i < slots.length && i < offered.size(); i++) {
            if (slots[i] < inv.getSize()) {
                inv.setItem(slots[i], offered.get(i));
            }
        }
    }

    private static void place(Inventory inv, TradeMenu layout, String button, String state,
                              Map<String, String> placeholders) {
        int slot = layout.slotOf(button);
        if (slot >= 0 && slot < inv.getSize()) {
            inv.setItem(slot, item(layout.look(button, state).fill(placeholders)));
        }
    }

    private static ItemStack item(TradeMenu.Look look) {
        ItemStack stack = new ItemStack(look.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (look.name() != null) {
                meta.setDisplayName(look.name());
            }
            if (!look.lore().isEmpty()) {
                meta.setLore(look.lore());
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
