package com.mystipixel.royaltrade.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BinaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs writes on one background thread. Writes are numbered in submission order; a write submitted while
 * an older one is still queued is merged into it, so the disk never goes back to an older state.
 */
public final class BackgroundWriter<T> {

    public interface Sink<T> {
        void write(T data) throws IOException;
    }

    private final String label;
    private final Sink<T> sink;
    // (queued, newer) -> what to write instead; for a full snapshot that is just the newer one
    private final BinaryOperator<T> merge;
    private final Logger logger;
    private final ExecutorService thread;

    private final Object lock = new Object();
    private T queued;
    private long queuedSeq;
    private long requested;
    private long durable;
    private long failed;
    private boolean draining;
    private boolean closed;
    private final TreeMap<Long, List<Runnable>> waiting = new TreeMap<>();

    /** label names the data in log lines, e.g. "escrow.yml"; threadName is e.g. "RoyalTrade-escrow-writer". */
    public BackgroundWriter(String label, String threadName, Sink<T> sink, BinaryOperator<T> merge, Logger logger) {
        this.label = label;
        this.sink = sink;
        this.merge = merge;
        this.logger = logger;
        this.thread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
    }

    public long submit(T data) {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException(label + " writer is closed");
            }
            queued = queued == null ? data : merge.apply(queued, data);
            queuedSeq = ++requested;
            if (!draining) {
                draining = true;
                thread.execute(this::drain);
            }
            return queuedSeq;
        }
    }

    public long requested() {
        synchronized (lock) {
            return requested;
        }
    }

    public long durable() {
        synchronized (lock) {
            return durable;
        }
    }

    // runs on the writer thread once write seq (or a newer one) is on disk; never if it never lands
    public void whenDurable(long seq, Runnable task) {
        synchronized (lock) {
            if (durable < seq) {
                waiting.computeIfAbsent(seq, k -> new ArrayList<>()).add(task);
                return;
            }
        }
        task.run();
    }

    /** Blocks until write seq (or a newer one) is on disk. Throws if its write failed. */
    public void await(long seq, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (lock) {
            while (durable < seq) {
                if (failed >= seq && !draining) {
                    throw new UncheckedIOException(new IOException(label + " write " + seq + " failed"));
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new IllegalStateException("Timed out waiting for " + label + " write " + seq);
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lock, left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted waiting for " + label + " write " + seq, e);
                }
            }
        }
    }

    public void close(long timeoutMillis) {
        synchronized (lock) {
            closed = true;
        }
        thread.shutdown();
        try {
            if (!thread.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                logger.severe(label + " writer did not finish in time; the last change may be lost.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void drain() {
        while (true) {
            T data;
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
                logger.log(Level.SEVERE, "Cannot write " + label + "; the next change retries it", e);
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
                    logger.log(Level.WARNING, "An after-write task for " + label + " failed", e);
                }
            }
        }
    }
}
