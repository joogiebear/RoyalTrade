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
    PaymentJournal journal() { return new PaymentJournal(folder); }
    @Test void rejectionSurvivesRestartAndCanBeRetried() {
        var j = journal(); j.begin(id, owner, other, "test");
        assertFalse(j.attempt(id, "credit", owner, 5, true, () -> false));
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
        assertThrows(RuntimeException.class, () -> j.attempt(id, "credit", owner, 5, true, () -> {
            try {
                Files.move(folder.resolve("pending"), folder.resolve("saved"));
                Files.createFile(folder.resolve("pending"));
            } catch (Exception e) { throw new RuntimeException(e); }
            return true;
        }));
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
}
