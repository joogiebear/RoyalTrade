package com.mystipixel.royaltrade.trade;

import com.mystipixel.royaltrade.data.Escrow;
import com.mystipixel.royaltrade.data.PaymentJournal;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import com.mystipixel.royaltrade.data.TradeLog;
import com.mystipixel.royaltrade.hooks.EconGuardHook;
import com.mystipixel.royaltrade.hooks.EconomyHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns every live trade, and performs the commit.
 *
 * <p>{@link #commit} is the only place items and money move. It runs as one synchronous main-thread
 * block from first check to last transfer, with no scheduling, no async economy call and no event in
 * the middle. Vault calls can reject or throw after earlier legs succeeded, so settlement receipts
 * are persisted and unresolved trades remain held rather than completed or charged again.
 */
public final class TradeManager {

    /** Why a commit refused. Callers turn this into a message. */
    public enum Failure {
        NONE,
        OFFLINE,
        NOT_SETTLING,
        INSUFFICIENT_FUNDS,
        NO_INVENTORY_SPACE,
        ECONOMY_ERROR,
        RECOVERY_REQUIRED
    }

    private final PaymentJournal payments;
    private final Logger logger;
    private final Set<UUID> recoveryPlayers = new HashSet<>();
    private final EconomyHook economy;
    private final Escrow escrow;
    private final TradeLog log;
    private final EconGuardHook econGuard;

    private final Map<UUID, TradeSession> byPlayer = new HashMap<>();
    private final Map<UUID, TradeSession> byId = new HashMap<>();
    private final Map<TradeSession, UUID> ids = new HashMap<>();
    private final Map<UUID, UUID> requests = new HashMap<>();      // target -> requester
    private final Map<UUID, Long> requestedAt = new HashMap<>();

    public TradeManager(EconomyHook economy, Escrow escrow, TradeLog log, EconGuardHook econGuard,
                        PaymentJournal payments, Logger logger) {
        this.payments = payments;
        this.logger = logger;
        for (var record : payments.pending().values()) {
            recoveryPlayers.add(UUID.fromString(record.getProperty("owner")));
            recoveryPlayers.add(UUID.fromString(record.getProperty("other")));
        }
        this.economy = economy;
        this.escrow = escrow;
        this.log = log;
        this.econGuard = econGuard;
    }

    // ------------------------------------------------------------------ sessions

    public TradeSession sessionOf(Player player) {
        return byPlayer.get(player.getUniqueId());
    }

    public boolean inTrade(Player player) {
        return recoveryPlayers.contains(player.getUniqueId()) || byPlayer.containsKey(player.getUniqueId());
    }

    public boolean needsRecovery(Player player) { return recoveryPlayers.contains(player.getUniqueId()); }

    public TradeSession open(Player first, Player second) {
        if (inTrade(first) || inTrade(second)) throw new IllegalStateException("Player already trading or held for recovery");
        TradeSession session = new TradeSession(first, second, System.currentTimeMillis());
        UUID id = UUID.randomUUID();
        byId.put(id, session);
        byPlayer.put(first.getUniqueId(), session);
        byPlayer.put(second.getUniqueId(), session);
        ids.put(session, id);
        return session;
    }

    public UUID idOf(TradeSession session) {
        return ids.get(session);
    }

    /** Persist a side's escrow after any change to it. */
    public void persist(TradeSession session) {
        UUID id = ids.get(session);
        if (id == null) {
            return;
        }
        escrow.hold(id, session.a().playerId(), session.a().offered());
        escrow.hold(id, session.b().playerId(), session.b().offered());
    }

    private void forget(TradeSession session) {
        UUID id = ids.get(session);
        if (id != null) {
            escrow.release(id);
            ids.remove(session);
            byId.remove(id);
        }
        byPlayer.remove(session.a().playerId());
        byPlayer.remove(session.b().playerId());
    }

    // ------------------------------------------------------------------ requests

    public void request(Player from, Player to) {
        requests.put(to.getUniqueId(), from.getUniqueId());
        requestedAt.put(to.getUniqueId(), System.currentTimeMillis());
    }

    public UUID pendingRequest(Player target, long expiryMillis) {
        UUID requester = requests.get(target.getUniqueId());
        if (requester == null) {
            return null;
        }
        Long at = requestedAt.get(target.getUniqueId());
        if (at == null || System.currentTimeMillis() - at > expiryMillis) {
            clearRequest(target);
            return null;
        }
        return requester;
    }

    public void clearRequest(Player target) {
        requests.remove(target.getUniqueId());
        requestedAt.remove(target.getUniqueId());
    }

    // ------------------------------------------------------------------ cancel

    /**
     * End a trade without moving anything. Escrow goes back to whoever offered it; if they are
     * offline or full it is queued and handed over on their next join, never dropped on the floor
     * where someone else could take it.
     */
    public void cancel(TradeSession session) {
        if (session.finished() || session.state() == TradeSession.State.RECOVERY) {
            return;
        }
        session.markCancelled();
        returnEscrow(session, session.a());
        returnEscrow(session, session.b());
        forget(session);
    }

    private void returnEscrow(TradeSession session, TradeSession.Side side) {
        List<ItemStack> items = session.drainItems(side);
        if (items.isEmpty()) {
            return;
        }
        Player player = Bukkit.getPlayer(side.playerId());
        if (player == null || !player.isOnline()) {
            escrow.queue(side.playerId(), items);
            return;
        }
        Map<Integer, ItemStack> leftover =
                player.getInventory().addItem(items.toArray(new ItemStack[0]));
        if (!leftover.isEmpty()) {
            escrow.queue(side.playerId(), new ArrayList<>(leftover.values()));
        }
    }

    // ------------------------------------------------------------------ commit

    /**
     * Move the goods. One block, no yielding.
     *
     * <p>Both debits and credits must be confirmed before item delivery. A rejected second debit
     * can abort only after its refund succeeds. Partial/unknown outcomes hold escrow for operators.
     */
    public Failure commit(TradeSession session) {
        if (session.state() != TradeSession.State.SETTLING || !ids.containsKey(session)) {
            return Failure.NOT_SETTLING;
        }

        Player pa = Bukkit.getPlayer(session.a().playerId());
        Player pb = Bukkit.getPlayer(session.b().playerId());
        if (pa == null || pb == null || !pa.isOnline() || !pb.isOnline()) {
            return Failure.OFFLINE;
        }

        double coinsA = session.a().coins();
        double coinsB = session.b().coins();

        // --- checks, all before any mutation -------------------------------
        if (!economy.has(pa, coinsA) || !economy.has(pb, coinsB)) {
            return Failure.INSUFFICIENT_FUNDS;
        }
        // Each player receives the other's items. Their own offer already left their inventory, so
        // the space it vacated counts — which is why this is simulated against live contents.
        if (!hasSpaceFor(pa, session.b().offered()) || !hasSpaceFor(pb, session.a().offered())) {
            return Failure.NO_INVENTORY_SPACE;
        }

        UUID id = ids.get(session);
        UUID attempt = UUID.randomUUID();
        try {
            persist(session);
            payments.begin(attempt, pa.getUniqueId(), pb.getUniqueId(), "trade:" + id);
        } catch (RuntimeException e) {
            logger.log(java.util.logging.Level.SEVERE, "Cannot persist trade intent; no payment attempted", e);
            return Failure.ECONOMY_ERROR;
        }
        // Also blocks reentrant cancel/commit from a provider callback.
        session.markRecovery();
        List<ItemStack> toA;
        List<ItemStack> toB;
        try {
            if (!payments.attempt(attempt, "debit-a", pa.getUniqueId(), coinsA, false,
                    () -> economy.withdraw(pa, coinsA))) {
                payments.complete(attempt);
                session.resumeAfterRejectedPayment();
                return Failure.ECONOMY_ERROR;
            }
            if (!payments.attempt(attempt, "debit-b", pb.getUniqueId(), coinsB, false,
                    () -> economy.withdraw(pb, coinsB))) {
                if (!payments.attempt(attempt, "refund-a", pa.getUniqueId(), coinsA, true,
                        () -> economy.deposit(pa, coinsA))) return holdRecovery(session, attempt, null);
                payments.complete(attempt);
                session.resumeAfterRejectedPayment();
                return Failure.ECONOMY_ERROR;
            }
            if (!payments.attempt(attempt, "credit-a", pa.getUniqueId(), coinsB, true,
                    () -> economy.deposit(pa, coinsB))) return holdRecovery(session, attempt, null);
            if (!payments.attempt(attempt, "credit-b", pb.getUniqueId(), coinsA, true,
                    () -> economy.deposit(pb, coinsA))) return holdRecovery(session, attempt, null);

            payments.delivering(attempt);
            toA = session.drainItems(session.b());
            toB = session.drainItems(session.a());
            give(pa, toA);
            give(pb, toB);
            forget(session); // Strict escrow persistence must succeed before closing the receipt.
            payments.complete(attempt);
            session.markCompleted();
        } catch (RuntimeException e) {
            return holdRecovery(session, attempt, e);
        }

        // Reporting is after the fact and must never affect the trade.
        log.record(id, pa, pb, toB, toA, coinsA, coinsB);
        econGuard.observe(pa, pb, coinsA, coinsB, toB.size(), toA.size());
        econGuard.observe(pb, pa, coinsB, coinsA, toA.size(), toB.size());
        return Failure.NONE;
    }

    private Failure holdRecovery(TradeSession session, UUID attempt, RuntimeException cause) {
        session.markRecovery();
        recoveryPlayers.add(session.a().playerId());
        recoveryPlayers.add(session.b().playerId());
        logger.log(java.util.logging.Level.SEVERE, "Trade held for payment reconciliation. Receipt "
                + attempt + "; session " + ids.get(session) + ". Do not automatically retry or return escrow.", cause);
        return Failure.RECOVERY_REQUIRED;
    }

    /**
     * Would these stacks fit? Simulated against a copy of the player's storage, because
     * {@code addItem} is the only thing that knows how stacking actually resolves.
     */
    private boolean hasSpaceFor(Player player, List<ItemStack> incoming) {
        if (incoming.isEmpty()) {
            return true;
        }
        Inventory probe = Bukkit.createInventory(null, 36);
        ItemStack[] storage = player.getInventory().getStorageContents();
        ItemStack[] copy = new ItemStack[storage.length];
        for (int i = 0; i < storage.length; i++) {
            copy[i] = storage[i] == null ? null : storage[i].clone();
        }
        probe.setContents(copy);
        List<ItemStack> clones = new ArrayList<>(incoming.size());
        for (ItemStack stack : incoming) {
            clones.add(stack.clone());
        }
        return probe.addItem(clones.toArray(new ItemStack[0])).isEmpty();
    }

    /** Hand items over. Space was checked, so leftovers are impossible — dropped rather than lost. */
    private void give(Player player, List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        Map<Integer, ItemStack> leftover =
                player.getInventory().addItem(items.toArray(new ItemStack[0]));
        for (ItemStack stack : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), stack);
        }
    }

    /** Every live session, for shutdown. */
    public List<TradeSession> active() {
        return List.copyOf(byId.values());
    }
}
