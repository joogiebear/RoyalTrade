package com.mystipixel.royaltrade.data;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** A journal on real files whose disk operations a test in another package can watch or slow down. */
public final class WatchedJournal {

    public record Op(String kind, Path file, String thread) {}

    public interface BeforeOp {
        void run(String kind) throws IOException;
    }

    private WatchedJournal() {
    }

    public static PaymentJournal open(Path folder, Consumer<Op> afterEach) {
        return open(folder, kind -> { }, afterEach);
    }

    public static PaymentJournal open(Path folder, BeforeOp beforeEach, Consumer<Op> afterEach) {
        PaymentJournal.Disk disk = new PaymentJournal.Disk() {
            @Override public void write(Path file, String data) throws IOException {
                beforeEach.run("write");
                PaymentJournal.FILES.write(file, data);
                afterEach.accept(new Op("write", file, Thread.currentThread().getName()));
            }
            @Override public void move(Path from, Path to) throws IOException {
                beforeEach.run("move");
                PaymentJournal.FILES.move(from, to);
                afterEach.accept(new Op("move", from, Thread.currentThread().getName()));
            }
            @Override public void delete(Path file) throws IOException {
                beforeEach.run("delete");
                PaymentJournal.FILES.delete(file);
                afterEach.accept(new Op("delete", file, Thread.currentThread().getName()));
            }
        };
        return new PaymentJournal(folder, Logger.getLogger("test"), disk, 5_000);
    }
}
