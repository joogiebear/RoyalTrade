package com.mystipixel.royaltrade.data;

import com.mystipixel.royaltrade.util.BackgroundWriter;
import com.mystipixel.royaltrade.util.DataFiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Write-ahead payment receipts, one small file per settlement. Only an explicit REJECTED result is
 * safe to retry; IN_FLIGHT after a crash is held for reconciliation, since Vault has no idempotency key.
 * Files are written on a background thread, but every method that guards a payment or delivery returns
 * only once that guard is on disk.
 */
public final class PaymentJournal {

    // settlement blocks the main thread on this; past it the step the record guards does not run
    private static final long FLUSH_TIMEOUT_MILLIS = 10_000L;

    /** What the writer thread does to the files. Tests swap it to watch or break the disk. */
    interface Disk {
        void write(Path file, String data) throws IOException;
        void move(Path from, Path to) throws IOException;
        void delete(Path file) throws IOException;
    }

    static final Disk FILES = new Disk() {
        @Override public void write(Path file, String data) throws IOException {
            DataFiles.writeAtomically(file, data);
        }
        @Override public void move(Path from, Path to) throws IOException {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        }
        @Override public void delete(Path file) throws IOException {
            Files.deleteIfExists(file);
        }
    };

    /** A payment leg named up front, see {@link #begin(UUID, UUID, UUID, String, List, boolean)}. */
    public record Leg(String step, UUID account, double amount, boolean credit) {}

    // queued work for one record; a newer change to the same record is merged into a queued one
    private record Change(String content, boolean close, boolean discard) {
        Change then(Change next) {
            if (next.discard) return next;
            return new Change(next.content != null ? next.content : content, close || next.close, false);
        }
    }

    private final Path pending;
    private final Path receipts;
    private final Logger logger;
    private final Disk disk;
    private final long timeoutMillis;
    private final BackgroundWriter<Map<UUID, Change>> writer;
    // open records as this process last wrote them; the files may still be catching up
    private final Map<UUID, Properties> open = new HashMap<>();
    // open records with a payment result not yet queued; the next write of the record carries it
    private final Set<UUID> unsaved = new HashSet<>();
    // records whose latest write failed, until a newer write of the same record lands
    private final Map<UUID, IOException> failed = new ConcurrentHashMap<>();
    // property prefixes already IN_FLIGHT on disk from the intent write whose step has not run yet
    private final Map<UUID, Set<String>> planned = new HashMap<>();

    public PaymentJournal(Path folder, Logger logger) {
        this(folder, logger, FILES, FLUSH_TIMEOUT_MILLIS);
    }

    PaymentJournal(Path folder, Logger logger, Disk disk, long timeoutMillis) {
        pending = folder.resolve("pending");
        receipts = folder.resolve("receipts");
        this.logger = logger;
        this.disk = disk;
        this.timeoutMillis = timeoutMillis;
        writer = new BackgroundWriter<>("payment journal", "RoyalTrade-payment-writer",
                this::apply, PaymentJournal::merge, logger);
        try {
            Files.createDirectories(pending);
            Files.createDirectories(receipts);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        pending(); // Validate existing records before accepting any new payment.
    }

    public synchronized void begin(UUID id, UUID owner, UUID other, String reason) {
        begin(id, owner, other, reason, Map.of());
    }

    public synchronized void begin(UUID id, UUID owner, UUID other, String reason, Map<String, String> context) {
        begin(id, owner, other, reason, context, List.of(), false);
    }

    /**
     * Writes the intent with every non-zero leg, and the delivery if {@code delivery}, already marked
     * IN_FLIGHT, so their {@link #attempt} and {@link #delivering} calls wait on no write of their own.
     * A crash then leaves every planned leg IN_FLIGHT, which is held like any unknown outcome. A planned
     * step that never ran is dropped from the record by its next write.
     */
    public synchronized void begin(UUID id, UUID owner, UUID other, String reason, List<Leg> legs, boolean delivery) {
        begin(id, owner, other, reason, Map.of(), legs, delivery);
    }

    private void begin(UUID id, UUID owner, UUID other, String reason, Map<String, String> context,
                       List<Leg> legs, boolean delivery) {
        Properties existing = read(id);
        if (existing != null) {
            if (!owner.toString().equals(existing.getProperty("owner"))
                    || !Objects.toString(other, "").equals(existing.getProperty("other"))
                    || !reason.equals(existing.getProperty("reason"))) {
                throw new IllegalStateException("Payment ID reused with different parties/reason: " + id);
            }
            for (var entry : context.entrySet()) {
                if (!entry.getValue().equals(existing.getProperty("context." + entry.getKey()))) {
                    throw new IllegalStateException("Payment context changed: " + id);
                }
            }
            return;
        }
        Properties p = new Properties();
        p.setProperty("id", id.toString());
        p.setProperty("owner", owner.toString());
        p.setProperty("other", Objects.toString(other, ""));
        p.setProperty("reason", reason);
        context.forEach((key, value) -> p.setProperty("context." + key, value));
        Set<String> steps = new HashSet<>();
        for (Leg leg : legs) {
            checkLeg(leg.step(), leg.amount());
            if (leg.amount() == 0) continue;
            String base = "leg." + leg.step() + ".";
            markInFlight(p, base, leg.account(), leg.amount(), leg.credit());
            steps.add(base);
        }
        if (delivery) {
            p.setProperty("delivery", "IN_FLIGHT");
            steps.add("delivery");
        }
        if (!steps.isEmpty()) planned.put(id, steps);
        await(id, store(id, p));
    }

    public synchronized boolean attempt(UUID id, String step, UUID account, double amount,
                                        boolean credit, BooleanSupplier call) {
        checkLeg(step, amount);
        Properties p = Objects.requireNonNull(record(id), "Missing payment intent");
        String base = "leg." + step + ".";
        String identity = account + ":" + Double.toString(amount) + ":" + credit;
        if (p.containsKey(base + "identity") && !identity.equals(p.getProperty(base + "identity"))) {
            throw new IllegalStateException("Payment leg changed: " + id + "/" + step);
        }
        String status = p.getProperty(base + "status");
        if ("SUCCESS".equals(status)) return true;
        if (status != null && !Set.of("SUCCESS", "REJECTED", "IN_FLIGHT").contains(status)) {
            throw new IllegalStateException("Invalid payment status: " + id);
        }
        // planned by begin: its IN_FLIGHT mark is already on disk. Unplanned before the call, so a throw
        // from the provider leaves it IN_FLIGHT for good
        boolean guarded = "IN_FLIGHT".equals(status) && unplan(id, base);
        if ("IN_FLIGHT".equals(status) && !guarded) {
            throw new IllegalStateException("Unknown payment outcome: " + id + "/" + step);
        }
        if (Files.exists(receipts.resolve(id + ".properties"))) {
            throw new IllegalStateException("Settlement is already closed: " + id);
        }
        if (!guarded) {
            markInFlight(p, base, account, amount, credit);
            // a zero leg moves nothing, so it has nothing to guard
            if (amount > 0) await(id, store(id, p)); // A failed or late write prevents the provider call.
        }
        boolean success = amount == 0 || call.getAsBoolean(); // Exceptions deliberately leave IN_FLIGHT.
        p.setProperty(base + "status", success ? "SUCCESS" : "REJECTED");
        // goes out with the next guarded write, complete() or writeLater(); a crash first leaves IN_FLIGHT
        open.put(id, p);
        unsaved.add(id);
        return success;
    }

    /** Mark the delivery boundary before touching player inventories. Never automatically replay it. */
    public synchronized void delivering(UUID id) {
        Properties p = Objects.requireNonNull(record(id));
        if (unplan(id, "delivery")) return;
        p.setProperty("delivery", "IN_FLIGHT");
        await(id, store(id, p));
    }

    public synchronized void complete(UUID id) {
        Properties p = open.get(id);
        if (p == null) {
            if (Files.exists(receipts.resolve(id + ".properties"))) return;
            p = Objects.requireNonNull(load(id));
        }
        if (dropUnrun(id, p)) unsaved.add(id);
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("leg.") && key.endsWith(".status") && "IN_FLIGHT".equals(p.getProperty(key))) {
                throw new IllegalStateException("Cannot close an unknown payment: " + id);
            }
        }
        String content = unsaved.remove(id) ? serialise(p) : null;
        await(id, writer.submit(Map.of(id, new Change(content, true, false))));
        open.remove(id);
    }

    /** Drop a record no payment was attempted under, after {@link #begin} failed or timed out. */
    public synchronized void discard(UUID id) {
        Properties p = open.get(id);
        if (p == null) return;
        dropUnrun(id, p);
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("leg.")) throw new IllegalStateException("Cannot discard an attempted payment: " + id);
        }
        open.remove(id);
        unsaved.remove(id);
        writer.submit(Map.of(id, new Change(null, false, true)));
    }

    /** Queues the record's latest state without waiting, e.g. once a settlement is held. */
    public synchronized void writeLater(UUID id) {
        Properties p = open.get(id);
        if (p != null && dropUnrun(id, p)) unsaved.add(id);
        if (unsaved.remove(id)) writer.submit(Map.of(id, new Change(serialise(open.get(id)), false, false)));
    }

    /** Waits for queued writes, then lists the open records on disk. */
    public synchronized Map<UUID, Properties> pending() {
        flush();
        Map<UUID, Properties> out = new LinkedHashMap<>();
        try (var files = Files.list(pending)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".properties")).sorted().toList()) {
                UUID id = UUID.fromString(file.getFileName().toString().replace(".properties", ""));
                Properties p = load(id);
                UUID.fromString(p.getProperty("owner"));
                if (!p.getProperty("other", "").isEmpty()) UUID.fromString(p.getProperty("other"));
                out.put(id, p);
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return out;
    }

    public synchronized Properties read(UUID id) {
        Properties p = open.get(id);
        return p != null ? (Properties) p.clone() : load(id);
    }

    /** Blocks until every change so far has been written (or has failed and been logged). */
    public synchronized void flush() {
        for (UUID id : List.copyOf(unsaved)) writeLater(id);
        writer.await(writer.requested(), timeoutMillis);
    }

    /** Writes what is still unwritten and stops the writer: the disable path. */
    public synchronized void close() {
        for (UUID id : List.copyOf(unsaved)) writeLater(id);
        writer.close(timeoutMillis);
    }

    // the record as last written; an open one is loaded from disk the first time this process uses it
    private Properties record(UUID id) {
        Properties p = open.get(id);
        if (p != null) return p;
        p = load(id);
        if (p != null && Files.exists(pending.resolve(id + ".properties"))) open.put(id, p);
        return p;
    }

    private static void checkLeg(String step, double amount) {
        if (!step.matches("[a-zA-Z0-9_-]+") || !Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException("Invalid payment leg");
        }
    }

    private static void markInFlight(Properties p, String base, UUID account, double amount, boolean credit) {
        p.setProperty(base + "identity", account + ":" + Double.toString(amount) + ":" + credit);
        p.setProperty(base + "account", account.toString());
        p.setProperty(base + "amount", Double.toString(amount));
        p.setProperty(base + "credit", Boolean.toString(credit));
        p.setProperty(base + "status", "IN_FLIGHT");
    }

    // true if the step was planned and had not run yet; it counts as running from here on
    private boolean unplan(UUID id, String prefix) {
        Set<String> steps = planned.get(id);
        if (steps == null || !steps.remove(prefix)) return false;
        if (steps.isEmpty()) planned.remove(id);
        return true;
    }

    // a planned step that never ran moved nothing, so the record need not hold it IN_FLIGHT
    private boolean dropUnrun(UUID id, Properties p) {
        Set<String> steps = planned.remove(id);
        if (steps == null) return false;
        for (String key : p.stringPropertyNames()) {
            if (steps.stream().anyMatch(key::startsWith)) p.remove(key);
        }
        return true;
    }

    private long store(UUID id, Properties p) {
        open.put(id, p);
        unsaved.remove(id);
        return writer.submit(Map.of(id, new Change(serialise(p), false, false)));
    }

    private void await(UUID id, long seq) {
        writer.await(seq, timeoutMillis);
        IOException e = failed.get(id);
        if (e != null) throw new UncheckedIOException(e);
    }

    private Properties load(UUID id) {
        Path file = pending.resolve(id + ".properties");
        if (!Files.exists(file)) file = receipts.resolve(id + ".properties");
        if (!Files.exists(file)) return null;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        if (!id.toString().equals(p.getProperty("id")) || p.getProperty("reason") == null) {
            throw new IllegalStateException("Corrupt payment record: " + file);
        }
        UUID.fromString(p.getProperty("owner"));
        String other = Objects.requireNonNull(p.getProperty("other"));
        if (!other.isEmpty()) UUID.fromString(other);
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("leg.") && key.endsWith(".status")) {
                if (!Set.of("SUCCESS", "REJECTED", "IN_FLIGHT").contains(p.getProperty(key))) {
                    throw new IllegalStateException("Corrupt payment status: " + file);
                }
                String base = key.substring(0, key.length() - "status".length());
                UUID.fromString(p.getProperty(base + "account"));
                double amount = Double.parseDouble(p.getProperty(base + "amount"));
                if (!Double.isFinite(amount) || amount < 0 || p.getProperty(base + "identity") == null
                        || !Set.of("true", "false").contains(p.getProperty(base + "credit"))) {
                    throw new IllegalStateException("Corrupt payment leg: " + file);
                }
            }
        }
        return p;
    }

    // runs on the writer thread; a failure is kept per record so only the settlement waiting on it fails
    private void apply(Map<UUID, Change> batch) {
        for (Map.Entry<UUID, Change> entry : batch.entrySet()) {
            UUID id = entry.getKey();
            Change change = entry.getValue();
            Path file = pending.resolve(id + ".properties");
            try {
                if (change.discard()) {
                    disk.delete(file);
                } else {
                    if (change.content() != null) disk.write(file, change.content());
                    if (change.close()) disk.move(file, receipts.resolve(id + ".properties"));
                }
                failed.remove(id);
            } catch (IOException e) {
                failed.put(id, e);
                logger.log(Level.SEVERE, "Cannot write payment record " + id, e);
            }
        }
    }

    private static Map<UUID, Change> merge(Map<UUID, Change> queued, Map<UUID, Change> newer) {
        Map<UUID, Change> out = new LinkedHashMap<>(queued);
        newer.forEach((id, change) -> out.merge(id, change, Change::then));
        return out;
    }

    // store(OutputStream) escapes everything outside printable ASCII, so the text is plain ASCII
    private static String serialise(Properties p) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            p.store(bytes, "Royal payment receipt - do not replay IN_FLIGHT automatically");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toString(StandardCharsets.ISO_8859_1);
    }
}
