package com.mystipixel.royaltrade.trade;

import com.mystipixel.royaltrade.data.*;
import com.mystipixel.royaltrade.hooks.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Settlement against a real journal: what is on disk at every payment, and what a restart finds after
// a crash at any point. Each disk operation is captured, so every state a kill -9 can leave is checked.
class SettlementJournalTest {
    @TempDir Path folder;
    EconomyHook economy;
    Escrow escrow;
    EconGuardHook guard;
    TradeManager manager;
    PaymentJournal journal;
    Player a, b;
    TradeSession session;
    MockedStatic<Bukkit> bukkit;
    volatile boolean failWrites;
    UUID tradeId;
    final List<WatchedJournal.Op> ops = Collections.synchronizedList(new ArrayList<>());
    // the pending and receipts folders after each disk operation, starting with the empty state
    final List<Map<String, String>> states = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach void setup() {
        economy = mock(EconomyHook.class); escrow = mock(Escrow.class); guard = mock(EconGuardHook.class);
        journal = WatchedJournal.open(folder.resolve("payments"),
                kind -> { if (failWrites) throw new IOException("disk full"); },
                op -> { ops.add(op); states.add(snapshot()); });
        manager = new TradeManager(economy, escrow, mock(TradeLog.class), guard, journal, Logger.getLogger("test"));
        states.add(snapshot());
        a = player("a"); b = player("b");
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer(a.getUniqueId())).thenReturn(a);
        bukkit.when(() -> Bukkit.getPlayer(b.getUniqueId())).thenReturn(b);
        Inventory probe = mock(Inventory.class);
        when(probe.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
        bukkit.when(() -> Bukkit.createInventory(null, 36)).thenReturn(probe);
        when(economy.has(any(), anyDouble())).thenReturn(true);
        when(guard.allow(any())).thenReturn(true);
        session = manager.open(a, b);
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        try {
            var field = TradeSession.Side.class.getDeclaredField("offered");
            field.setAccessible(true);
            ((List<ItemStack>) field.get(session.a())).add(item);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    @AfterEach void close() { bukkit.close(); journal.close(); }

    private Player player(String name) {
        Player p = mock(Player.class); UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id); when(p.getName()).thenReturn(name);
        when(p.isOnline()).thenReturn(true);
        PlayerInventory inv = mock(PlayerInventory.class);
        when(p.getInventory()).thenReturn(inv);
        when(inv.getStorageContents()).thenReturn(new ItemStack[36]);
        when(inv.addItem(any(ItemStack[].class))).thenAnswer(call -> {
            assertEquals("IN_FLIGHT", onDisk().getProperty("delivery"), "items moved before the delivery mark was on disk");
            return new HashMap<>();
        });
        return p;
    }

    private Map<String, String> snapshot() {
        Map<String, String> out = new TreeMap<>();
        for (String dir : List.of("pending", "receipts")) {
            Path path = folder.resolve("payments").resolve(dir);
            if (!Files.isDirectory(path)) continue;
            try (var files = Files.list(path)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".properties")).toList()) {
                    out.put(dir + "/" + file.getFileName(), Files.readString(file));
                }
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        return out;
    }

    // the one open receipt, read from disk rather than from the journal's memory
    private Properties onDisk() throws IOException {
        try (var files = Files.list(folder.resolve("payments").resolve("pending"))) {
            Path file = files.filter(f -> f.toString().endsWith(".properties")).findFirst().orElseThrow();
            Properties p = new Properties();
            p.load(new StringReader(Files.readString(file)));
            return p;
        }
    }

    // every economy call checks that its own leg is already IN_FLIGHT on disk
    private void pays(boolean debitA, boolean debitB, boolean creditA, boolean creditB, boolean refundA) {
        when(economy.withdraw(eq(a), anyDouble())).thenAnswer(call -> leg("debit-a", debitA));
        when(economy.withdraw(eq(b), anyDouble())).thenAnswer(call -> leg("debit-b", debitB));
        when(economy.deposit(eq(a), anyDouble())).thenAnswer(call ->
                leg(onDisk().getProperty("leg.refund-a.status") != null ? "refund-a" : "credit-a",
                        onDisk().getProperty("leg.refund-a.status") != null ? refundA : creditA));
        when(economy.deposit(eq(b), anyDouble())).thenAnswer(call -> leg("credit-b", creditB));
    }

    private boolean leg(String step, boolean result) throws IOException {
        assertEquals("IN_FLIGHT", onDisk().getProperty("leg." + step + ".status"), step + " ran before its intent was on disk");
        return result;
    }

    private TradeManager.Failure settle(double coinsA, double coinsB) {
        session.setCoins(session.a(), coinsA);
        session.setCoins(session.b(), coinsB);
        session.beginSettling(1);
        tradeId = manager.idOf(session);
        return manager.commit(session);
    }

    // A crash can stop the disk at any of the captured states. Each one must be a state the synchronous
    // journal also passed through, and a restart from it must hold or free the players as it did then.
    private void assertEveryCrashPointRecoversAsBefore(List<String> legOrder) throws IOException {
        journal.flush();
        int i = 0;
        for (Map<String, String> state : List.copyOf(states)) {
            String open = state.entrySet().stream().filter(e -> e.getKey().startsWith("pending/"))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            assertTrue(state.keySet().stream().filter(k -> k.startsWith("pending/")).count() <= 1);
            if (open != null) assertSynchronousJournalState(open, legOrder);

            Path restart = folder.resolve("restart-" + i++);
            for (var file : state.entrySet()) {
                Path target = restart.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue());
            }
            PaymentJournal reopened = new PaymentJournal(restart, Logger.getLogger("test"));
            try {
                TradeManager restarted = new TradeManager(economy, escrow, mock(TradeLog.class), guard, reopened,
                        Logger.getLogger("test"));
                Set<String> held = new HashSet<>();
                reopened.pending().values().forEach(p -> held.add(p.getProperty("reason")));
                assertEquals(open != null, restarted.needsRecovery(a), "state " + state);
                assertEquals(open != null, restarted.needsRecovery(b), "state " + state);
                assertEquals(open != null ? Set.of("trade:" + tradeId) : Set.of(), held);
            } finally {
                reopened.close();
            }
        }
    }

    // the synchronous journal wrote after every single step: some legs settled in order, at most the last
    // one IN_FLIGHT, and the delivery mark only once every leg had settled
    private static void assertSynchronousJournalState(String text, List<String> legOrder) throws IOException {
        Properties p = new Properties();
        p.load(new StringReader(text));
        int present = 0;
        boolean inFlight = false;
        for (String step : legOrder) {
            String status = p.getProperty("leg." + step + ".status");
            if (status == null) break;
            assertFalse(inFlight, "a leg was written after an unsettled one: " + p);
            inFlight = "IN_FLIGHT".equals(status);
            present++;
        }
        for (String step : legOrder.subList(present, legOrder.size())) {
            assertNull(p.getProperty("leg." + step + ".status"), "legs out of order: " + p);
        }
        if (p.getProperty("delivery") != null) {
            assertEquals(legOrder.size(), present, "delivery marked before every leg: " + p);
            assertFalse(inFlight, "delivery marked with a leg unsettled: " + p);
        }
    }

    @Test void theMainThreadDoesNoJournalFileWork() {
        pays(true, true, true, true, true);
        String main = Thread.currentThread().getName();
        assertEquals(TradeManager.Failure.NONE, settle(10, 20));
        journal.flush();
        assertFalse(ops.isEmpty());
        for (WatchedJournal.Op op : ops) {
            assertNotEquals(main, op.thread());
            assertEquals("RoyalTrade-payment-writer", op.thread());
        }
    }

    @Test void aPaidTradeRecoversAsBeforeFromACrashAtAnyStep() throws IOException {
        pays(true, true, true, true, true);
        assertEquals(TradeManager.Failure.NONE, settle(10, 20));
        assertTrue(states.get(states.size() - 1).keySet().stream().allMatch(k -> k.startsWith("receipts/")));
        assertEveryCrashPointRecoversAsBefore(List.of("debit-a", "debit-b", "credit-a", "credit-b"));
    }

    @Test void anItemOnlyTradeRecoversAsBeforeFromACrashAtAnyStep() throws IOException {
        pays(true, true, true, true, true);
        assertEquals(TradeManager.Failure.NONE, settle(0, 0));
        verify(economy, never()).withdraw(any(), anyDouble());
        assertEveryCrashPointRecoversAsBefore(List.of("debit-a", "debit-b", "credit-a", "credit-b"));
    }

    @Test void aRefundedTradeRecoversAsBeforeFromACrashAtAnyStep() throws IOException {
        pays(true, false, true, true, true);
        assertEquals(TradeManager.Failure.ECONOMY_ERROR, settle(10, 20));
        verify(economy).deposit(a, 10);
        assertTrue(journal.pending().isEmpty());
        assertEveryCrashPointRecoversAsBefore(List.of("debit-a", "debit-b", "refund-a"));
    }

    @Test void aHeldTradeRecoversAsBeforeFromACrashAtAnyStep() throws IOException {
        pays(true, true, true, false, true);
        assertEquals(TradeManager.Failure.RECOVERY_REQUIRED, settle(10, 20));
        // the held result reaches disk on its own, without another journal call
        long deadline = System.currentTimeMillis() + 5_000;
        while (!"REJECTED".equals(onDisk().getProperty("leg.credit-b.status")) && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals("REJECTED", onDisk().getProperty("leg.credit-b.status"));
        assertEveryCrashPointRecoversAsBefore(List.of("debit-a", "debit-b", "credit-a", "credit-b"));
    }

    @Test void aJournalThatCannotBeginRefusesBeforeAnyPayment() {
        pays(true, true, true, true, true);
        failWrites = true;
        assertEquals(TradeManager.Failure.ECONOMY_ERROR, settle(10, 20));
        failWrites = false;
        verify(economy, never()).withdraw(any(), anyDouble());
        verify(b.getInventory(), never()).addItem(any(ItemStack[].class));
        assertTrue(journal.pending().isEmpty());
        assertFalse(manager.needsRecovery(a));
        assertEquals(1, session.a().offered().size());
    }

    // mvn test -Dtest=SettlementJournalTest -Droyaltrade.bench=true [-Droyaltrade.bench.ms=20]: each
    // journal disk operation takes that long, and each line is how long settlement held the calling thread
    @Test void benchMainThreadTimeWithASlowDisk() {
        Assumptions.assumeTrue(Boolean.getBoolean("royaltrade.bench"));
        long diskMs = Long.getLong("royaltrade.bench.ms", 20);
        bukkit.close();
        for (double[] coins : new double[][] {{10, 20}, {10, 20}, {10, 0}, {0, 0}}) {
            setup();
            journal.close();
            journal = WatchedJournal.open(folder.resolve("bench-" + coins[0] + "-" + coins[1]),
                    kind -> { try { Thread.sleep(diskMs); } catch (InterruptedException e) { throw new IOException(e); } },
                    ops::add);
            manager = new TradeManager(economy, escrow, mock(TradeLog.class), guard, journal, Logger.getLogger("test"));
            session = manager.open(a, b);
            when(economy.withdraw(any(), anyDouble())).thenReturn(true);
            when(economy.deposit(any(), anyDouble())).thenReturn(true);
            when(a.getInventory().addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
            when(b.getInventory().addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
            ops.clear();
            long start = System.nanoTime();
            assertEquals(TradeManager.Failure.NONE, settle(coins[0], coins[1]));
            long heldMs = (System.nanoTime() - start) / 1_000_000;
            journal.flush();
            long onMain = ops.stream().filter(op -> op.thread().equals(Thread.currentThread().getName())).count();
            System.out.printf("coins %.0f/%.0f: main thread held %d ms, %d journal disk ops (%d on the main thread)%n",
                    coins[0], coins[1], heldMs, ops.size(), onMain);
            bukkit.close();
        }
        bukkit = mockStatic(Bukkit.class);
    }
}
