package com.mystipixel.royaltrade.trade;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One trade between two players, and the rules that make it safe.
 *
 * <p>The whole plugin exists to stop one attack: agreeing to a trade and then changing it before it
 * commits. Every defence here follows from that.
 *
 * <ul>
 *   <li><b>Any change clears both confirmations.</b> {@link #touch()} runs on every mutation, so a
 *       swapped item cannot ride in behind a confirmation the other player already gave. This is the
 *       single rule that matters; the rest are depth.</li>
 *   <li><b>A settle window.</b> Once both sides confirm, the trade freezes in {@link State#SETTLING}
 *       and refuses further edits. Without it, an edit landing in the same tick as the commit is a
 *       race, and races are how dupes happen.</li>
 *   <li><b>Escrow.</b> Offered items leave the player's inventory and live here. That stops the same
 *       item being offered in two trades at once, which references-into-inventory cannot.</li>
 * </ul>
 *
 * <p>This class holds state and enforces the rules. It never touches inventories or money — see
 * {@code TradeManager} for the commit, which is a single synchronous block on purpose.
 */
public final class TradeSession {

    /** Where a trade is. Edits are only legal in {@link #ACTIVE}. */
    public enum State {
        /** Both windows open, either side may change their offer. */
        ACTIVE,
        /** Both confirmed. Frozen: the trade may complete or be cancelled, not edited. */
        SETTLING,
        /** Items and money have moved. Terminal. */
        COMPLETED,
        /** Nothing moved; escrow has been returned. Terminal. */
        CANCELLED
    }

    /** One player's half of the trade. */
    public static final class Side {

        private final UUID playerId;
        private final String playerName;
        private final List<ItemStack> offered = new ArrayList<>();
        private double coins;
        private boolean confirmed;

        Side(Player player) {
            this.playerId = player.getUniqueId();
            this.playerName = player.getName();
        }

        public UUID playerId() {
            return playerId;
        }

        public String playerName() {
            return playerName;
        }

        /** The escrowed items. Never handed out mutable — callers get a copy. */
        public List<ItemStack> offered() {
            return List.copyOf(offered);
        }

        public double coins() {
            return coins;
        }

        public boolean confirmed() {
            return confirmed;
        }

        public boolean isEmpty() {
            return offered.isEmpty() && coins <= 0;
        }
    }

    private final Side a;
    private final Side b;
    private final long createdAt;

    private State state = State.ACTIVE;
    private long settlingSince;

    public TradeSession(Player first, Player second, long now) {
        this.a = new Side(first);
        this.b = new Side(second);
        this.createdAt = now;
    }

    public State state() {
        return state;
    }

    public Side a() {
        return a;
    }

    public Side b() {
        return b;
    }

    public long createdAt() {
        return createdAt;
    }

    public long settlingSince() {
        return settlingSince;
    }

    public Side sideOf(UUID playerId) {
        if (a.playerId.equals(playerId)) {
            return a;
        }
        return b.playerId.equals(playerId) ? b : null;
    }

    public Side other(Side side) {
        return side == a ? b : a;
    }

    public boolean involves(UUID playerId) {
        return a.playerId.equals(playerId) || b.playerId.equals(playerId);
    }

    public boolean editable() {
        return state == State.ACTIVE;
    }

    // ------------------------------------------------------------------ mutations

    /**
     * Clear both confirmations. Called by every mutation below.
     *
     * <p>Both, not just the side that changed: a player who has confirmed has agreed to a specific
     * deal, and the deal is no longer that one. Clearing only the mutating side would leave the other
     * player's agreement attached to terms they never saw — which is the scam.
     */
    private void touch() {
        a.confirmed = false;
        b.confirmed = false;
    }

    /** Escrow an item. Returns false if the trade is frozen, in which case the caller keeps it. */
    public boolean addItem(Side side, ItemStack stack) {
        if (!editable() || stack == null || stack.getType().isAir()) {
            return false;
        }
        side.offered.add(stack.clone());
        touch();
        return true;
    }

    /**
     * Take an escrowed item back out. Returns the stack to hand to the player, or null if the index
     * is stale — two clicks in the same tick can both target the same slot.
     */
    public ItemStack removeItem(Side side, int index) {
        if (!editable() || index < 0 || index >= side.offered.size()) {
            return null;
        }
        ItemStack removed = side.offered.remove(index);
        touch();
        return removed;
    }

    /** Everything a side has escrowed, emptied out. Used when returning escrow on cancel. */
    public List<ItemStack> drainItems(Side side) {
        List<ItemStack> out = List.copyOf(side.offered);
        side.offered.clear();
        return out;
    }

    public boolean setCoins(Side side, double amount) {
        if (!editable() || amount < 0) {
            return false;
        }
        side.coins = amount;
        touch();
        return true;
    }

    /**
     * Record a confirmation. Returns true when this was the second one, so the caller knows to enter
     * the settle window.
     *
     * <p>An empty trade cannot be confirmed: two players both confirming nothing is never intended,
     * and it is a cheap way to make "trade completed" appear in someone's log.
     */
    public boolean confirm(Side side) {
        if (!editable() || (a.isEmpty() && b.isEmpty())) {
            return false;
        }
        side.confirmed = true;
        return a.confirmed && b.confirmed;
    }

    public void unconfirm(Side side) {
        if (editable()) {
            side.confirmed = false;
        }
    }

    // ------------------------------------------------------------------ state

    public void beginSettling(long now) {
        if (state == State.ACTIVE) {
            state = State.SETTLING;
            settlingSince = now;
        }
    }

    /** Back to editable — used when the settle window is broken by a disconnect or a cancel. */
    public void abortSettling() {
        if (state == State.SETTLING) {
            state = State.ACTIVE;
            settlingSince = 0L;
            touch();
        }
    }

    public void markCompleted() {
        state = State.COMPLETED;
    }

    public void markCancelled() {
        state = State.CANCELLED;
    }

    public boolean finished() {
        return state == State.COMPLETED || state == State.CANCELLED;
    }
}
