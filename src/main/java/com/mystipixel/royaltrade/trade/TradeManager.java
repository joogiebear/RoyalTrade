package com.mystipixel.royaltrade.trade;

import com.mystipixel.royaltrade.data.Escrow;
import com.mystipixel.royaltrade.data.PaymentJournal;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import com.mystipixel.royaltrade.data.TradeLog;
import com.mystipixel.royaltrade.hooks.EconGuardHook;
import com.mystipixel.royaltrade.hooks.EconomyHook;
import com.mystipixel.royaltrade.hooks.SkyblockSaveHook;
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
 * Owns every live trade. {@link #commit} is the only place items and money move, in one synchronous
 * main-thread block; a settlement that fails partway is held for recovery, never retried.
 */
public final class TradeManager {

    /** Why a commit refused. */
    public enum Failure {
        NONE,
        OFFLINE,
        NOT_SETTLING,
        INSUFFICIENT_FUNDS,
        NO_INVENTORY_SPACE,
        ECONOMY_ERROR,
        RECOVERY_REQUIRED,
        /** EconGuard's veto refused one of the players. */
        RESTRICTED
    }

    /** Runs a task on the main thread after a number of ticks. */
    public interface Scheduler {
        void later(Runnable task, long ticks);
    }

    // offer changes inside this window share one escrow write and one save per player
    private static final long WRITE_DELAY_TICKS = 10L;

    private final PaymentJournal payments;
    private final Logger logger;
    private final Set<UUID> recoveryPlayers = new HashSet<>();
    private final EconomyHook economy;
    private final Escrow escrow;
    private final TradeLog log;
    private final EconGuardHook econGuard;
    private final SkyblockSaveHook skyblockSave;
    private final Scheduler scheduler;

    private final Map<UUID, TradeSession> byPlayer = new HashMap<>();
    private final Map<UUID, TradeSession> byId = new HashMap<>();
    private final Map<TradeSession, UUID> ids = new HashMap<>();
    // player -> the escrow write that must be on disk before their data is saved
    private final Map<UUID, Long> savesDue = new HashMap<>();
    // players whose offer changed since the last escrow write; their save waits for the next one
    private final Set<UUID> staged = new HashSet<>();
    // items taken back out of an offer; escrow keeps them until the owner's data is saved with them
    private final Map<UUID, List<ItemStack>> returned = new HashMap<>();
    private boolean writeScheduled;
    // target -> (requester -> sent at); several per target so a second request can't wipe the first
    private final Map<UUID, Map<UUID, Long>> requests = new HashMap<>();

    public TradeManager(EconomyHook economy, Escrow escrow, TradeLog log, EconGuardHook econGuard,
                        SkyblockSaveHook skyblockSave, PaymentJournal payments, Scheduler scheduler, Logger logger) {
        this.scheduler = scheduler;
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
        this.skyblockSave = skyblockSave;
    }

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

    /**
     * Escrow the current offers. Changes within half a second share one escrow write, and both
     * players' data is saved once that write is on disk, so a crash can't restore an escrowed item from a
     * stale player save. Escrow first: a crash between the writes duplicates rather than loses. Items
     * leaving escrow need the reverse order, see {@link #returnedTo}.
     */
    public void persist(TradeSession session) {
        UUID id = ids.get(session);
        if (id == null) {
            return;
        }
        escrow.stage(id, offers(session));
        staged.add(session.a().playerId());
        staged.add(session.b().playerId());
        scheduleWrite();
    }

    /**
     * Note an item taken back out of an offer, before {@link #persist}. Escrow keeps a copy until the
     * player's data has been saved with the item back in it.
     */
    public void returnedTo(Player player, ItemStack stack) {
        returned.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(stack);
    }

    private void scheduleWrite() {
        if (!writeScheduled) {
            writeScheduled = true;
            scheduler.later(this::writeStaged, WRITE_DELAY_TICKS);
        }
    }

    private void writeStaged() {
        writeScheduled = false;
        long seq = escrow.saveStaged();
        for (UUID player : staged) {
            savesDue.merge(player, seq, Math::max);
        }
        staged.clear();
        escrow.whenDurable(seq, this::saveDue);
    }

    // settlement: nothing may leave a player until escrow on disk holds exactly the offers and their saved
    // data agrees with it. The write cycle has normally done all of that, so this usually writes nothing
    private void persistNow(UUID id, TradeSession session) {
        UUID a = session.a().playerId();
        UUID b = session.b().playerId();
        if (escrow.isDurable() && !staged.contains(a) && !staged.contains(b)
                && !returned.containsKey(a) && !returned.containsKey(b)) {
            saveIfDue(a);
            saveIfDue(b);
            return;
        }
        holdEscrow(id, session);
        escrow.flush();
        staged.remove(a);
        staged.remove(b);
        savesDue.remove(a);
        savesDue.remove(b);
        saveInventories(session);
        // a held trade's escrow must show only the offers, not what was already taken back
        if (returned.remove(a) != null | returned.remove(b) != null) {
            holdEscrow(id, session);
            escrow.flush();
        }
    }

    // only called once escrow is fully on disk, so any save still owed is safe now
    private void saveIfDue(UUID player) {
        if (savesDue.remove(player) != null) {
            saveInventory(Bukkit.getPlayer(player));
        }
    }

    private long holdEscrow(UUID id, TradeSession session) {
        return escrow.hold(id, offers(session));
    }

    private Map<UUID, List<ItemStack>> offers(TradeSession session) {
        return Map.of(session.a().playerId(), withReturned(session.a()),
                session.b().playerId(), withReturned(session.b()));
    }

    private List<ItemStack> withReturned(TradeSession.Side side) {
        List<ItemStack> back = returned.get(side.playerId());
        if (back == null) {
            return side.offered();
        }
        List<ItemStack> all = new ArrayList<>(side.offered());
        all.addAll(back);
        return all;
    }

    // a player whose escrow changed again since waits for that newer write
    private void saveDue() {
        long durable = escrow.durableSeq();
        List<UUID> saved = new ArrayList<>();
        savesDue.entrySet().removeIf(due -> {
            if (due.getValue() > durable || staged.contains(due.getKey())) {
                return false;
            }
            saveInventory(Bukkit.getPlayer(due.getKey()));
            saved.add(due.getKey());
            return true;
        });
        saved.forEach(this::dropReturned);
    }

    // the player's saved data now has what they took back, so escrow can let go of it
    private void dropReturned(UUID player) {
        if (returned.remove(player) == null) {
            return;
        }
        TradeSession session = byPlayer.get(player);
        UUID id = session == null ? null : ids.get(session);
        if (id != null) {
            escrow.stage(id, offers(session));
            scheduleWrite();
        }
    }

    // a failed save is logged, not thrown
    private void saveInventory(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            player.saveData();
        } catch (RuntimeException e) {
            logger.log(java.util.logging.Level.WARNING, "Could not save " + player.getName()
                    + "'s data after a trade change; a crash before the next autosave may disagree"
                    + " with escrow.", e);
        }
    }

    private void saveInventories(TradeSession session) {
        saveInventory(Bukkit.getPlayer(session.a().playerId()));
        saveInventory(Bukkit.getPlayer(session.b().playerId()));
    }

    private void forget(TradeSession session) {
        UUID id = ids.get(session);
        if (id != null) {
            escrow.release(id);
        }
        unregister(session);
    }

    private void unregister(TradeSession session) {
        UUID id = ids.remove(session);
        if (id != null) {
            byId.remove(id);
        }
        for (UUID player : List.of(session.a().playerId(), session.b().playerId())) {
            byPlayer.remove(player);
            // both were just saved with everything back or delivered
            savesDue.remove(player);
            staged.remove(player);
            returned.remove(player);
        }
    }

    public void request(Player from, Player to, long expiryMillis) {
        pruneExpired(to.getUniqueId(), expiryMillis);
        requests.computeIfAbsent(to.getUniqueId(), k -> new HashMap<>())
                .put(from.getUniqueId(), System.currentTimeMillis());
    }

    public boolean hasRequest(Player target, Player requester, long expiryMillis) {
        pruneExpired(target.getUniqueId(), expiryMillis);
        Map<UUID, Long> waiting = requests.get(target.getUniqueId());
        return waiting != null && waiting.containsKey(requester.getUniqueId());
    }

    public void clearRequest(Player target, Player requester) {
        Map<UUID, Long> waiting = requests.get(target.getUniqueId());
        if (waiting != null) {
            waiting.remove(requester.getUniqueId());
            if (waiting.isEmpty()) {
                requests.remove(target.getUniqueId());
            }
        }
    }

    /** Drop every request a departing player sent or was sent. */
    public void forgetRequests(UUID player) {
        requests.remove(player);
        requests.values().removeIf(waiting -> {
            waiting.remove(player);
            return waiting.isEmpty();
        });
    }

    private void pruneExpired(UUID target, long expiryMillis) {
        Map<UUID, Long> waiting = requests.get(target);
        if (waiting == null) {
            return;
        }
        long now = System.currentTimeMillis();
        waiting.values().removeIf(at -> now - at > expiryMillis);
        if (waiting.isEmpty()) {
            requests.remove(target);
        }
    }

    /**
     * End a trade without moving anything. Escrow goes back to whoever offered it, or is queued for
     * their next join if they are offline or full.
     */
    public void cancel(TradeSession session) {
        if (session.finished() || session.state() == TradeSession.State.RECOVERY) {
            return;
        }
        session.markCancelled();
        returnEscrow(session, session.a());
        returnEscrow(session, session.b());
        saveInventories(session);   // before escrow is released, see persist()
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

    /**
     * Move the goods in one block, no yielding. Debits and credits must all succeed before items are
     * delivered; a partial or unknown outcome holds escrow for operators.
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

        // all checks before any mutation; EconGuard again since a player can be flagged mid-trade
        if (!econGuard.allow(pa) || !econGuard.allow(pb)) {
            return Failure.RESTRICTED;
        }
        if (!economy.has(pa, coinsA) || !economy.has(pb, coinsB)) {
            return Failure.INSUFFICIENT_FUNDS;
        }
        // simulated against live contents: space freed by a player's own escrowed offer counts
        if (!hasSpaceFor(pa, session.b().offered()) || !hasSpaceFor(pb, session.a().offered())) {
            return Failure.NO_INVENTORY_SPACE;
        }

        UUID id = ids.get(session);
        UUID attempt = UUID.randomUUID();
        try {
            persistNow(id, session);
        } catch (RuntimeException e) {
            logger.log(java.util.logging.Level.SEVERE, "Cannot persist trade intent; no payment attempted", e);
            return Failure.ECONOMY_ERROR;
        }
        // one intent write guards every leg and the delivery; the results go out with the close
        List<PaymentJournal.Leg> legs = List.of(
                new PaymentJournal.Leg("debit-a", pa.getUniqueId(), coinsA, false),
                new PaymentJournal.Leg("debit-b", pb.getUniqueId(), coinsB, false),
                new PaymentJournal.Leg("credit-a", pa.getUniqueId(), coinsB, true),
                new PaymentJournal.Leg("credit-b", pb.getUniqueId(), coinsA, true));
        try {
            payments.begin(attempt, pa.getUniqueId(), pb.getUniqueId(), "trade:" + id, legs, true);
        } catch (RuntimeException e) {
            // a timed-out write may still land; nothing was paid, so it must not hold the players later
            payments.discard(attempt);
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
            // before escrow is released, see persist()
            saveInventory(pa);
            saveInventory(pb);
            // the release must be on disk before the receipt closes
            escrow.release(id);
            escrow.flush();
            unregister(session);
            payments.complete(attempt);
            session.markCompleted();
        } catch (RuntimeException e) {
            return holdRecovery(session, attempt, e);
        }

        // RoyalSkyblock keeps inventories in its own profile store, which player.saveData() doesn't cover
        skyblockSave.saveNow(pa.getUniqueId(), pb.getUniqueId());

        // Reporting is after the fact and must never affect the trade.
        log.record(id, pa, pb, toB, toA, coinsA, coinsB);
        econGuard.observe(pa, pb, coinsA, coinsB, toB, toA);
        econGuard.observe(pb, pa, coinsB, coinsA, toA, toB);
        return Failure.NONE;
    }

    private Failure holdRecovery(TradeSession session, UUID attempt, RuntimeException cause) {
        payments.writeLater(attempt);
        session.markRecovery();
        recoveryPlayers.add(session.a().playerId());
        recoveryPlayers.add(session.b().playerId());
        logger.log(java.util.logging.Level.SEVERE, "Trade held for payment reconciliation. Receipt "
                + attempt + "; session " + ids.get(session) + ". Do not automatically retry or return escrow.", cause);
        return Failure.RECOVERY_REQUIRED;
    }

    // simulated on a copy because addItem is the only thing that knows how stacking resolves
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

    // space was checked, so leftovers shouldn't happen; dropped rather than lost if they do
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
