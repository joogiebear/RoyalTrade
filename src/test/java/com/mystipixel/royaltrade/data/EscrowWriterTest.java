package com.mystipixel.royaltrade.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class EscrowWriterTest {
    final List<String> written = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    EscrowWriter writer;

    // the first write blocks until release, so later submits pile up behind it
    private EscrowWriter blockingWriter() {
        AtomicBoolean first = new AtomicBoolean(true);
        return new EscrowWriter(data -> {
            if (first.getAndSet(false)) {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            written.add(data);
        }, Logger.getLogger("test"));
    }

    @AfterEach void stop() {
        release.countDown();
        if (writer != null) writer.close(5_000);
    }

    @Test void changesQueuedBehindAWriteCoalesceIntoTheNewest() throws Exception {
        writer = blockingWriter();
        writer.submit("1");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        writer.submit("2");
        writer.submit("3");
        long last = writer.submit("4");
        release.countDown();
        writer.await(last, 5_000);
        assertEquals(List.of("1", "4"), written);
        assertEquals(last, writer.durable());
    }

    @Test void writesLandInSubmissionOrderAndEndOnTheNewest() {
        writer = new EscrowWriter(data -> {
            written.add(data);
            Thread.onSpinWait();
        }, Logger.getLogger("test"));
        long last = 0;
        for (int i = 1; i <= 500; i++) {
            last = writer.submit(Integer.toString(i));
        }
        writer.await(last, 5_000);
        int previous = 0;
        for (String data : written) {
            int value = Integer.parseInt(data);
            assertTrue(value > previous, "an older snapshot landed after a newer one: " + written);
            previous = value;
        }
        assertEquals(500, previous);
    }

    @Test void awaitReturnsOnlyOnceTheWriteIsOnDisk() throws Exception {
        writer = blockingWriter();
        long seq = writer.submit("1");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = new Thread(() -> { writer.await(seq, 5_000); done.countDown(); });
        waiter.start();
        assertFalse(done.await(200, TimeUnit.MILLISECONDS));
        assertTrue(written.isEmpty());
        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("1"), written);
    }

    @Test void aSlowDiskTimesOutInsteadOfHangingTheCaller() throws Exception {
        writer = blockingWriter();
        long seq = writer.submit("1");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> writer.await(seq, 50));
    }

    @Test void aFailedWriteIsReportedAndTheNextChangeRetries() {
        AtomicBoolean fail = new AtomicBoolean(true);
        writer = new EscrowWriter(data -> {
            if (fail.get()) throw new IOException("disk full");
            written.add(data);
        }, Logger.getLogger("test"));
        long first = writer.submit("1");
        assertThrows(UncheckedIOException.class, () -> writer.await(first, 5_000));
        assertEquals(0, writer.durable());
        fail.set(false);
        long second = writer.submit("2");
        writer.await(second, 5_000);
        assertEquals(List.of("2"), written);
    }

    @Test void aCallbackRunsOnlyOnceItsWriteHasLanded() throws Exception {
        writer = blockingWriter();
        long first = writer.submit("1");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        long second = writer.submit("2");
        CountDownLatch firstLanded = new CountDownLatch(1);
        CountDownLatch secondLanded = new CountDownLatch(1);
        List<String> seenBySecond = new ArrayList<>();
        writer.whenDurable(first, firstLanded::countDown);
        writer.whenDurable(second, () -> { seenBySecond.addAll(written); secondLanded.countDown(); });
        assertEquals(1, firstLanded.getCount());
        release.countDown();
        assertTrue(secondLanded.await(5, TimeUnit.SECONDS));
        assertTrue(firstLanded.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("1", "2"), seenBySecond);
    }

    @Test void aCallbackForAWriteAlreadyOnDiskRunsAtOnce() {
        writer = new EscrowWriter(written::add, Logger.getLogger("test"));
        long seq = writer.submit("1");
        writer.await(seq, 5_000);
        AtomicBoolean ran = new AtomicBoolean();
        writer.whenDurable(seq, () -> ran.set(true));
        assertTrue(ran.get());
    }

    @Test void aThrowingCallbackDoesNotStopLaterWrites() {
        writer = new EscrowWriter(written::add, Logger.getLogger("test"));
        long seq = writer.submit("1");
        writer.whenDurable(seq + 1, () -> { throw new IllegalStateException("plugin disabled"); });
        writer.await(writer.submit("2"), 5_000);
        writer.await(writer.submit("3"), 5_000);
        assertEquals("3", written.get(written.size() - 1));
    }
}
