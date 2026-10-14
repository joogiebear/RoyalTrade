package com.mystipixel.royaltrade.data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PaymentJournalTest {
    @TempDir Path folder;
    UUID id = UUID.randomUUID(), owner = UUID.randomUUID(), other = UUID.randomUUID();
    final List<PaymentJournal> opened = new ArrayList<>();
    PaymentJournal journal() {
        PaymentJournal j = new PaymentJournal(folder, java.util.logging.Logger.getLogger("test"));
        opened.add(j);
        return j;
    }
    @org.junit.jupiter.api.AfterEach void closeAll() { opened.forEach(PaymentJournal::close); }
    @Test void rejectionSurvivesRestartAndCanBeRetried() {
        var j = journal(); j.begin(id, owner, other, "test");
        assertFalse(j.attempt(id, "credit", owner, 5, true, () -> false));
        j.close();
        var restarted = journal();
        assertEquals("REJECTED", restarted.read(id).getProperty("leg.credit.status"));
        assertTrue(restarted.attempt(id, "credit", owner, 5, true, () -> true));
        restarted.complete(id); assertTrue(restarted.pending().isEmpty());
    }
    @Test void acceptedPaymentNeverRunsTwiceIncludingAfterRestart() {
        var j = journal(); j.begin(id, owner, other, "test");
        AtomicInteger calls = new AtomicInteger();
        assertTrue(j.attempt(id, "credit", owner, 5, true, () -> { calls.incrementAndGet(); return true; }));
        j.complete(id);
        assertTrue(journal().attempt(id, "credit", owner, 5, true, () -> { calls.incrementAndGet(); return true; }));
        assertEquals(1, calls.get());
    }
    @Test void providerExceptionLeavesUnknownOutcomeThatCannotBeRetriedOrClosed() {
        var j = journal(); j.begin(id, owner, other, "test");
        assertThrows(IllegalStateException.class, () -> j.attempt(id, "credit", owner, 5, true,
                () -> { throw new IllegalStateException("provider may have paid"); }));
        j.close();
        var restarted = journal();
        assertEquals("IN_FLIGHT", restarted.read(id).getProperty("leg.credit.status"));
        assertThrows(IllegalStateException.class, () -> restarted.attempt(id, "credit", owner, 5, true,
                () -> { fail("Must not pay twice"); return true; }));
        assertThrows(IllegalStateException.class, () -> restarted.complete(id));
    }
    @Test void intentWriteFailurePreventsProviderCall() throws Exception {
        var j = journal(); j.begin(id, owner, other, "test");
        Files.move(folder.resolve("pending"), folder.resolve("saved"));
        Files.createFile(folder.resolve("pending"));
        assertThrows(RuntimeException.class, () -> j.attempt(id, "credit", owner, 5, true,
                () -> { fail("No payment without persisted intent"); return true; }));
    }
    @Test void resultWriteFailureLeavesPersistedUnknownGuard() throws Exception {
        var j = journal(); j.begin(id, owner, other, "test");
        // the result write is not waited for; its failure stops the receipt from closing instead
        assertTrue(j.attempt(id, "credit", owner, 5, true, () -> {
            try {
                Files.move(folder.resolve("pending"), folder.resolve("saved"));
                Files.createFile(folder.resolve("pending"));
            } catch (Exception e) { throw new RuntimeException(e); }
            return true;
        }));
        assertThrows(RuntimeException.class, () -> j.complete(id));
        j.close();
        Files.delete(folder.resolve("pending"));
        Files.move(folder.resolve("saved"), folder.resolve("pending"));
        assertEquals("IN_FLIGHT", journal().read(id).getProperty("leg.credit.status"));
    }
    @Test void changedAmountAccountOrDirectionCannotReuseReceipt() {
        var j = journal(); j.begin(id, owner, other, "test");
        j.attempt(id, "credit", owner, 5, true, () -> true);
        assertThrows(IllegalStateException.class, () -> j.attempt(id, "credit", owner, 6, true, () -> true));
        assertThrows(IllegalStateException.class, () -> j.attempt(id, "credit", other, 5, true, () -> true));
        assertThrows(IllegalStateException.class, () -> j.attempt(id, "credit", owner, 5, false, () -> true));
    }
    @Test void corruptRecordFailsStartupClosed() throws Exception {
        var j = journal(); j.begin(id, owner, other, "test");
        Files.writeString(folder.resolve("pending").resolve(id + ".properties"), "id=garbage\n");
        assertThrows(RuntimeException.class, this::journal);
    }
    @Test void zeroLegDoesNotContactProviderAndNonfiniteAmountsAreRejected() {
        var j = journal(); j.begin(id, owner, other, "test");
        assertTrue(j.attempt(id, "zero", owner, 0, true, () -> { fail("No zero payment"); return true; }));
        for (double amount : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> j.attempt(id, "bad", owner, amount, true, () -> true));
    }

    private Properties onDisk(String dir) {
        Properties p = new Properties();
        try (var in = Files.newInputStream(folder.resolve(dir).resolve(id + ".properties"))) { p.load(in); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        return p;
    }
    private List<PaymentJournal.Leg> plan() {
        return List.of(new PaymentJournal.Leg("debit", owner, 5, false), new PaymentJournal.Leg("credit", other, 5, true),
                new PaymentJournal.Leg("free", other, 0, true));
    }
    @Test void aPlannedIntentGuardsEveryLegAndTheDeliveryInOneWrite() {
        var j = journal(); j.begin(id, owner, other, "test", plan(), true);
        Properties intent = onDisk("pending");
        assertEquals("IN_FLIGHT", intent.getProperty("leg.debit.status"));
        assertEquals("IN_FLIGHT", intent.getProperty("leg.credit.status"));
        assertNull(intent.getProperty("leg.free.status"));
        assertEquals("IN_FLIGHT", intent.getProperty("delivery"));
        assertTrue(j.attempt(id, "debit", owner, 5, false, () -> true));
        assertTrue(j.attempt(id, "credit", other, 5, true, () -> true));
        assertTrue(j.attempt(id, "free", other, 0, true, () -> { fail("No zero payment"); return true; }));
        j.delivering(id);
        j.complete(id);
        Properties closed = onDisk("receipts");
        assertEquals("SUCCESS", closed.getProperty("leg.debit.status"));
        assertEquals("SUCCESS", closed.getProperty("leg.credit.status"));
        assertEquals("IN_FLIGHT", closed.getProperty("delivery"));
    }
    @Test void plannedStepsThatNeverRanAreDroppedWhenTheReceiptCloses() {
        var j = journal(); j.begin(id, owner, other, "test", plan(), true);
        assertFalse(j.attempt(id, "debit", owner, 5, false, () -> false));
        j.complete(id);
        Properties closed = onDisk("receipts");
        assertEquals("REJECTED", closed.getProperty("leg.debit.status"));
        assertNull(closed.getProperty("leg.credit.status"));
        assertNull(closed.getProperty("delivery"));
    }
    @Test void aHeldRecordKeepsOnlyTheStepsThatRan() {
        var j = journal(); j.begin(id, owner, other, "test", plan(), true);
        assertTrue(j.attempt(id, "debit", owner, 5, false, () -> true));
        assertFalse(j.attempt(id, "credit", other, 5, true, () -> false));
        j.writeLater(id); j.close();
        Properties held = journal().read(id);
        assertEquals("SUCCESS", held.getProperty("leg.debit.status"));
        assertEquals("REJECTED", held.getProperty("leg.credit.status"));
        assertNull(held.getProperty("delivery"));
    }
    @Test void aPlannedLegThatThrowsStaysUnknownAcrossRestart() {
        var j = journal(); j.begin(id, owner, other, "test", plan(), true);
        assertThrows(IllegalStateException.class, () -> j.attempt(id, "debit", owner, 5, false,
                () -> { throw new IllegalStateException("provider may have paid"); }));
        assertThrows(IllegalStateException.class, () -> j.attempt(id, "debit", owner, 5, false,
                () -> { fail("Must not pay twice"); return true; }));
        j.close();
        var restarted = journal();
        assertEquals("IN_FLIGHT", restarted.read(id).getProperty("leg.debit.status"));
        // never called, but a restart cannot know that, so it is held as unknown too
        assertThrows(IllegalStateException.class, () -> restarted.attempt(id, "credit", other, 5, true,
                () -> { fail("Must not pay after a restart"); return true; }));
        assertThrows(IllegalStateException.class, () -> restarted.complete(id));
    }
    @Test void aPlannedLegCannotBePaidWithDifferentTerms() {
        var j = journal(); j.begin(id, owner, other, "test", plan(), true);
        assertThrows(IllegalStateException.class, () -> j.attempt(id, "debit", owner, 6, false,
                () -> { fail("Changed leg must not pay"); return true; }));
    }
    @Test void anIntentWithNothingRunCanStillBeDiscarded() {
        var j = journal(); j.begin(id, owner, other, "test", plan(), true);
        j.discard(id);
        assertTrue(j.pending().isEmpty());
    }
}
