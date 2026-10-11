package com.mystipixel.royaltrade.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PaymentJournalWriterTest {
    @TempDir Path folder;
    final UUID id = UUID.randomUUID(), owner = UUID.randomUUID(), other = UUID.randomUUID();
    final CountDownLatch release = new CountDownLatch(1);
    final List<String> written = Collections.synchronizedList(new ArrayList<>());
    final List<String> threads = Collections.synchronizedList(new ArrayList<>());
    final List<PaymentJournal> opened = new ArrayList<>();

    @AfterEach void stop() {
        release.countDown();
        opened.forEach(PaymentJournal::close);
    }

    // writes after the first `free` ones block until release; every write is recorded once it lands
    private PaymentJournal journal(int free, long timeoutMillis) {
        AtomicInteger writes = new AtomicInteger();
        PaymentJournal.Disk disk = new PaymentJournal.Disk() {
            @Override public void write(Path file, String data) throws IOException {
                if (writes.incrementAndGet() > free) {
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                }
                PaymentJournal.FILES.write(file, data);
                written.add(data);
                threads.add(Thread.currentThread().getName());
            }
            @Override public void move(Path from, Path to) throws IOException {
                PaymentJournal.FILES.move(from, to);
                threads.add(Thread.currentThread().getName());
            }
            @Override public void delete(Path file) throws IOException {
                PaymentJournal.FILES.delete(file);
                threads.add(Thread.currentThread().getName());
            }
        };
        PaymentJournal j = new PaymentJournal(folder, Logger.getLogger("test"), disk, timeoutMillis);
        opened.add(j);
        return j;
    }

    private Properties onDisk() throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(folder.resolve("pending").resolve(id + ".properties"))) {
            p.load(in);
        }
        return p;
    }

    @Test void aPaidLegRunsOnlyOnceItsIntentIsOnDisk() {
        var j = journal(Integer.MAX_VALUE, 5_000);
        j.begin(id, owner, other, "test");
        List<String> seen = new ArrayList<>();
        assertTrue(j.attempt(id, "debit", owner, 5, false, () -> {
            try {
                seen.add(onDisk().getProperty("leg.debit.status"));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            return true;
        }));
        assertEquals(List.of("IN_FLIGHT"), seen);
    }

    @Test void aSlowIntentWriteHoldsThePaymentBackUntilItLands() throws Exception {
        var j = journal(1, 5_000);
        j.begin(id, owner, other, "test");
        AtomicBoolean paid = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        Thread settlement = new Thread(() -> {
            j.attempt(id, "debit", owner, 5, false, () -> { paid.set(true); return true; });
            done.countDown();
        });
        settlement.start();
        assertFalse(done.await(200, TimeUnit.MILLISECONDS));
        assertFalse(paid.get());
        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(paid.get());
    }

    @Test void anIntentWriteThatTimesOutNeverPays() {
        var j = journal(1, 100);
        j.begin(id, owner, other, "test");
        assertThrows(IllegalStateException.class, () -> j.attempt(id, "debit", owner, 5, false,
                () -> { fail("No payment before the intent is on disk"); return true; }));
        // the leg stays unknown, so it can never be retried as a fresh charge
        assertThrows(IllegalStateException.class, () -> j.attempt(id, "debit", owner, 5, false,
                () -> { fail("Must not pay twice"); return true; }));
    }

    @Test void aZeroLegDoesNotWaitButDeliveryDoes() {
        var j = journal(1, 100);
        j.begin(id, owner, other, "test");
        assertTrue(j.attempt(id, "debit", owner, 0, false, () -> { fail("No zero payment"); return true; }));
        assertThrows(IllegalStateException.class, () -> j.delivering(id));
    }

    @Test void queuedChangesMergeAndTheDiskOnlyMovesForward() throws Exception {
        var j = journal(2, 5_000);
        j.begin(id, owner, other, "test");
        // zero legs are not waited for, so these pile up behind the blocked write
        j.attempt(id, "first", owner, 0, false, () -> true);
        j.attempt(id, "second", owner, 0, false, () -> true);
        j.attempt(id, "third", owner, 0, false, () -> true);
        j.attempt(id, "fourth", owner, 0, false, () -> true);
        release.countDown();
        j.flush();
        assertTrue(written.size() < 1 + 4 * 2, "queued writes were not merged: " + written.size());
        int legs = 0;
        for (String data : written) {
            Properties p = new Properties();
            p.load(new java.io.StringReader(data));
            int now = (int) p.stringPropertyNames().stream().filter(k -> k.endsWith(".status")).count();
            assertTrue(now >= legs, "an older record landed after a newer one");
            legs = now;
        }
        Properties last = onDisk();
        for (String step : List.of("first", "second", "third", "fourth")) {
            assertEquals("SUCCESS", last.getProperty("leg." + step + ".status"));
        }
    }

    @Test void aBeginThatLandsAfterItsTimeoutIsDiscarded() {
        var j = journal(0, 100);
        assertThrows(IllegalStateException.class, () -> j.begin(id, owner, other, "test"));
        j.discard(id);
        release.countDown();
        j.flush();
        assertTrue(j.pending().isEmpty());
        assertNull(j.read(id));
    }

    @Test void anAttemptedRecordCannotBeDiscarded() {
        var j = journal(Integer.MAX_VALUE, 5_000);
        j.begin(id, owner, other, "test");
        j.attempt(id, "debit", owner, 5, false, () -> true);
        assertThrows(IllegalStateException.class, () -> j.discard(id));
        assertEquals(1, j.pending().size());
    }

    @Test void theCallingThreadNeverTouchesTheFiles() {
        var j = journal(Integer.MAX_VALUE, 5_000);
        j.begin(id, owner, other, "test");
        j.attempt(id, "debit", owner, 5, false, () -> true);
        j.attempt(id, "credit", other, 5, true, () -> true);
        j.delivering(id);
        j.complete(id);
        assertFalse(threads.isEmpty());
        assertTrue(threads.stream().allMatch("RoyalTrade-payment-writer"::equals), threads.toString());
        assertTrue(Files.exists(folder.resolve("receipts").resolve(id + ".properties")));
    }

    @Test void aFailedWriteOfOneRecordDoesNotFailAnother() throws Exception {
        UUID broken = UUID.randomUUID();
        PaymentJournal.Disk disk = new PaymentJournal.Disk() {
            @Override public void write(Path file, String data) throws IOException {
                if (file.getFileName().toString().startsWith(broken.toString())) throw new IOException("bad sector");
                PaymentJournal.FILES.write(file, data);
            }
            @Override public void move(Path from, Path to) throws IOException { PaymentJournal.FILES.move(from, to); }
            @Override public void delete(Path file) throws IOException { PaymentJournal.FILES.delete(file); }
        };
        var j = new PaymentJournal(folder, Logger.getLogger("test"), disk, 5_000);
        opened.add(j);
        assertThrows(java.io.UncheckedIOException.class, () -> j.begin(broken, owner, other, "test"));
        j.begin(id, owner, other, "test");
        assertTrue(j.attempt(id, "debit", owner, 5, false, () -> true));
        j.complete(id);
    }

    @Test void aWriteCutOffByACrashIsIgnoredAtStartup() throws Exception {
        Files.createDirectories(folder.resolve("pending"));
        Files.writeString(folder.resolve("pending").resolve(id + ".properties.tmp"), "id=half");
        assertTrue(journal(Integer.MAX_VALUE, 5_000).pending().isEmpty());
    }
}
