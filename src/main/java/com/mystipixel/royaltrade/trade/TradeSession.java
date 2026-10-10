package com.mystipixel.royaltrade.trade;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One trade between two players. Any change clears both confirmations, confirming is delayed after a
 * change, both-confirmed trades freeze in {@link State#SETTLING}, and offered items sit in escrow here.
 * Inventories and money are only touched by {@code TradeManager}.
 */
public final class TradeSession {

    // edits are only legal in ACTIVE
    public enum State {
        ACTIVE,
        // frozen: may complete or be cancelled, not edited
        SETTLING,
        COMPLETED,
        // unresolved settlement: no editing, cancellation or automatic replay
        RECOVERY,
        CANCELLED
    }

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

        /** The escrowed items, as a copy. */
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
    private long changedAt;

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

    /** When either offer last changed, or 0 if neither has. */
    public long changedAt() {
        return changedAt;
    }

    /**
     * Milliseconds until confirming is allowed again after the last change, or 0 if it is now. A
     * confirm click already in flight when the offer changes would otherwise accept the new terms.
     */
    public long confirmBlockedFor(long now, long delayMillis) {
        return changedAt == 0 ? 0 : Math.max(0, changedAt + delayMillis - now);
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

    // every mutation calls this; clear both sides, or the other player's confirm
    // stays attached to terms they never saw
    private void touch() {
        a.confirmed = false;
        b.confirmed = false;
        changedAt = System.currentTimeMillis();
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
     * is stale (two clicks in the same tick can both target the same slot).
     */
    public ItemStack removeItem(Side side, int index) {
        if (!editable() || index < 0 || index >= side.offered.size()) {
            return null;
        }
        ItemStack removed = side.offered.remove(index);
        touch();
        return removed;
    }

    /** Empties and returns a side's escrow, for returning it on cancel. */
    public List<ItemStack> drainItems(Side side) {
        List<ItemStack> out = List.copyOf(side.offered);
        side.offered.clear();
        return out;
    }

    public boolean setCoins(Side side, double amount) {
        if (!editable() || !Double.isFinite(amount) || amount < 0) {
            return false;
        }
        side.coins = amount;
        touch();
        return true;
    }

    /**
     * Record a confirmation. Returns true when this was the second one, so the caller knows to enter
     * the settle window. An empty trade cannot be confirmed.
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

    public void beginSettling(long now) {
        if (state == State.ACTIVE) {
            state = State.SETTLING;
            settlingSince = now;
        }
    }

    /** Back to editable, used when the settle window is broken by a disconnect or a cancel. */
    public void abortSettling() {
        if (state == State.SETTLING) {
            state = State.ACTIVE;
            settlingSince = 0L;
            touch();
        }
    }

    public void markRecovery() { state = State.RECOVERY; }

    void resumeAfterRejectedPayment() {
        if (state == State.RECOVERY) state = State.SETTLING;
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
