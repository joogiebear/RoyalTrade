package com.mystipixel.royaltrade.gui;

import com.mystipixel.royaltrade.hooks.EconomyHook;
import com.mystipixel.royaltrade.trade.TradeSession;
import com.mystipixel.royaltrade.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The trade window.
 *
 * <p>Each player gets their <em>own</em> inventory rather than both viewing one shared object, so
 * that "your offer" is always on the left for whoever is looking. A single shared inventory would
 * put the same player's items on the left for both of them, which reads as though you are about to
 * give away what you are receiving — precisely the confusion a trade window must not create.
 *
 * <p>The cost is that both views must be redrawn on every change. {@link #render} does both at once
 * and is called after every mutation, so the two windows cannot disagree about what is on the table.
 */
public final class TradeGui {

    public static final int SIZE = 54;

    /** Rows 1-4, columns 1-4. Where your offer sits, in your own window. */
    public static final int[] MINE = {
            0, 1, 2, 3,
            9, 10, 11, 12,
            18, 19, 20, 21,
            27, 28, 29, 30
    };
    /** Rows 1-4, columns 6-9. The other side, read-only. */
    public static final int[] THEIRS = {
            5, 6, 7, 8,
            14, 15, 16, 17,
            23, 24, 25, 26,
            32, 33, 34, 35
    };

    private static final int[] DIVIDER = {4, 13, 22, 31, 40, 49};
    public static final int MY_COINS = 37;
    public static final int THEIR_COINS = 43;
    public static final int MY_CONFIRM = 47;
    public static final int THEIR_CONFIRM = 51;
    public static final int CLOSE = 45;

    private final EconomyHook economy;
    private final Map<UUID, Inventory> views = new HashMap<>();

    public TradeGui(EconomyHook economy) {
        this.economy = economy;
    }

    public Inventory viewOf(Player player) {
        return views.get(player.getUniqueId());
    }

    public boolean isTradeView(Player player, Inventory inventory) {
        Inventory mine = views.get(player.getUniqueId());
        return mine != null && mine.equals(inventory);
    }

    public void open(TradeSession session, Player a, Player b) {
        views.put(a.getUniqueId(), Bukkit.createInventory(null, SIZE,
                Text.color("&8Trading with &f" + b.getName())));
        views.put(b.getUniqueId(), Bukkit.createInventory(null, SIZE,
                Text.color("&8Trading with &f" + a.getName())));
        render(session);
        a.openInventory(views.get(a.getUniqueId()));
        b.openInventory(views.get(b.getUniqueId()));
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
        Inventory inv = views.get(viewer.playerId());
        if (inv == null) {
            return;
        }
        TradeSession.Side them = session.other(viewer);
        inv.clear();

        ItemStack filler = pane(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, filler);
        }
        for (int slot : DIVIDER) {
            inv.setItem(slot, pane(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
        for (int slot : MINE) {
            inv.setItem(slot, null);
        }
        for (int slot : THEIRS) {
            inv.setItem(slot, null);
        }

        List<ItemStack> mine = viewer.offered();
        for (int i = 0; i < MINE.length && i < mine.size(); i++) {
            inv.setItem(MINE[i], mine.get(i));
        }
        List<ItemStack> theirs = them.offered();
        for (int i = 0; i < THEIRS.length && i < theirs.size(); i++) {
            inv.setItem(THEIRS[i], theirs.get(i));
        }

        inv.setItem(MY_COINS, coinItem("&eYour offer", viewer.coins(), true));
        inv.setItem(THEIR_COINS, coinItem("&eTheir offer", them.coins(), false));

        boolean settling = session.state() == TradeSession.State.SETTLING;
        inv.setItem(MY_CONFIRM, confirmItem(viewer.confirmed(), true, settling));
        inv.setItem(THEIR_CONFIRM, confirmItem(them.confirmed(), false, settling));
        inv.setItem(CLOSE, named(Material.BARRIER, "&cCancel trade",
                List.of("&7Nothing changes hands.", "&7Your items come straight back.")));
    }

    // ------------------------------------------------------------------ items

    private ItemStack coinItem(String title, double amount, boolean own) {
        List<String> lore = new ArrayList<>();
        lore.add("&f" + economy.format(amount));
        lore.add("");
        if (own) {
            lore.add("&eClick to change");
            lore.add("&8Changing this clears both confirmations.");
        } else {
            lore.add("&8What they are putting in.");
        }
        return named(amount > 0 ? Material.GOLD_INGOT : Material.GOLD_NUGGET, title, lore);
    }

    private ItemStack confirmItem(boolean confirmed, boolean own, boolean settling) {
        if (settling) {
            return named(Material.CLOCK, "&6Completing…",
                    List.of("&7Both sides have confirmed.", "&7The trade is locked while it settles."));
        }
        if (own) {
            return confirmed
                    ? named(Material.LIME_DYE, "&aYou have confirmed",
                    List.of("&7Waiting for the other player.", "", "&eClick to take it back"))
                    : named(Material.GRAY_DYE, "&7Confirm trade",
                    List.of("&7Check the right-hand side first.", "", "&eClick to confirm"));
        }
        return confirmed
                ? named(Material.LIME_DYE, "&aThey have confirmed", List.of("&7Waiting on you."))
                : named(Material.GRAY_DYE, "&7They have not confirmed", List.of("&7Give them a moment."));
    }

    private ItemStack pane(Material material, String name) {
        return named(material, name, List.of());
    }

    private ItemStack named(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color(name));
            if (!lore.isEmpty()) {
                List<String> coloured = new ArrayList<>(lore.size());
                for (String line : lore) {
                    coloured.add(Text.color(line));
                }
                meta.setLore(coloured);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** Which index in a side's offer a clicked slot maps to, or -1 if it is not an offer slot. */
    public static int mineIndex(int slot) {
        for (int i = 0; i < MINE.length; i++) {
            if (MINE[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    public static boolean isTheirs(int slot) {
        for (int s : THEIRS) {
            if (s == slot) {
                return true;
            }
        }
        return false;
    }
}
