package com.mystipixel.royaltrade.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes escrow snapshots on one background thread. Snapshots are numbered in submission order; a
 * snapshot submitted while an older one is still queued replaces it, so only the newest is written.
 */
final class EscrowWriter {

    interface Sink {
        void write(String data) throws IOException;
    }

    private final Sink sink;
    private final Logger logger;
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RoyalTrade-escrow-writer");
        t.setDaemon(true);
        return t;
    });

    private final Object lock = new Object();
    private String queued;
    private long queuedSeq;
    private long requested;
    private long durable;
    private long failed;
    private boolean draining;
    private boolean closed;
    private final TreeMap<Long, List<Runnable>> waiting = new TreeMap<>();

    EscrowWriter(Sink sink, Logger logger) {
        this.sink = sink;
        this.logger = logger;
    }

    long submit(String snapshot) {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Escrow writer is closed");
            }
            queued = snapshot;
            queuedSeq = ++requested;
            if (!draining) {
                draining = true;
                thread.execute(this::drain);
            }
            return queuedSeq;
        }
    }

    long requested() {
        synchronized (lock) {
            return requested;
        }
    }

    long durable() {
        synchronized (lock) {
            return durable;
        }
    }

    // runs on the writer thread once snapshot seq (or a newer one) is on disk; never if it never lands
    void whenDurable(long seq, Runnable task) {
        synchronized (lock) {
            if (durable < seq) {
                waiting.computeIfAbsent(seq, k -> new ArrayList<>()).add(task);
                return;
            }
        }
        task.run();
    }

    /** Blocks until snapshot seq (or a newer one) is on disk. Throws if its write failed. */
    void await(long seq, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (lock) {
            while (durable < seq) {
                if (failed >= seq && !draining) {
                    throw new UncheckedIOException(new IOException("Escrow write " + seq + " failed"));
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new IllegalStateException("Timed out waiting for escrow write " + seq);
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lock, left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted waiting for escrow write " + seq, e);
                }
            }
        }
    }

    void close(long timeoutMillis) {
        synchronized (lock) {
            closed = true;
        }
        thread.shutdown();
        try {
            if (!thread.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                logger.severe("escrow.yml writer did not finish in time; the last escrow change may be lost.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void drain() {
        while (true) {
            String data;
            long seq;
            synchronized (lock) {
                if (queued == null) {
                    draining = false;
                    lock.notifyAll();
                    return;
                }
                data = queued;
                seq = queuedSeq;
                queued = null;
            }
            List<Runnable> due = new ArrayList<>();
            try {
                sink.write(data);
                synchronized (lock) {
                    durable = seq;
                    Map<Long, List<Runnable>> landed = waiting.headMap(seq, true);
                    landed.values().forEach(due::addAll);
                    landed.clear();
                    lock.notifyAll();
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Cannot write escrow.yml; the next escrow change retries it", e);
                synchronized (lock) {
                    failed = seq;
                    lock.notifyAll();
                }
            }
            for (Runnable task : due) {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    // a throw here would leave draining set and stop every later write
                    logger.log(Level.WARNING, "An after-escrow-write task failed", e);
                }
            }
        }
    }
}
