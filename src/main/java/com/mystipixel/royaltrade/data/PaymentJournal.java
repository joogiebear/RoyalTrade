package com.mystipixel.royaltrade.data;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Write-ahead payment receipts. Only an explicit REJECTED result is safe to retry; IN_FLIGHT
 * after a crash/exception is deliberately held for reconciliation. Vault has no idempotency key.
 * One small file per settlement avoids rewriting an ever-growing global ledger on each payment.
 */
public final class PaymentJournal {
    private final Path pending;
    private final Path receipts;

    public PaymentJournal(Path folder) {
        pending = folder.resolve("pending");
        receipts = folder.resolve("receipts");
        try {
            Files.createDirectories(pending);
            Files.createDirectories(receipts);
            pending(); // Validate existing records before accepting any new payment.
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public synchronized void begin(UUID id, UUID owner, UUID other, String reason) {
        begin(id, owner, other, reason, Map.of());
    }

    public synchronized void begin(UUID id, UUID owner, UUID other, String reason, Map<String, String> context) {
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
        write(id, p);
    }

    public synchronized boolean attempt(UUID id, String step, UUID account, double amount,
                                        boolean credit, BooleanSupplier call) {
        if (!step.matches("[a-zA-Z0-9_-]+") || !Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException("Invalid payment leg");
        }
        Properties p = Objects.requireNonNull(read(id), "Missing payment intent");
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
        if ("IN_FLIGHT".equals(status)) throw new IllegalStateException("Unknown payment outcome: " + id + "/" + step);
        if (Files.exists(receipts.resolve(id + ".properties"))) {
            throw new IllegalStateException("Settlement is already closed: " + id);
        }
        p.setProperty(base + "identity", identity);
        p.setProperty(base + "account", account.toString());
        p.setProperty(base + "amount", Double.toString(amount));
        p.setProperty(base + "credit", Boolean.toString(credit));
        p.setProperty(base + "status", "IN_FLIGHT");
        write(id, p); // A failed write prevents the provider call.
        boolean success = amount == 0 || call.getAsBoolean(); // Exceptions deliberately leave IN_FLIGHT.
        p.setProperty(base + "status", success ? "SUCCESS" : "REJECTED");
        write(id, p); // A failed result write leaves the persisted IN_FLIGHT guard in place.
        return success;
    }

    /** Mark the delivery boundary before touching player inventories. Never automatically replay it. */
    public synchronized void delivering(UUID id) {
        Properties p = Objects.requireNonNull(read(id));
        p.setProperty("delivery", "IN_FLIGHT");
        write(id, p);
    }

    public synchronized void complete(UUID id) {
        if (Files.exists(receipts.resolve(id + ".properties"))) return;
        Properties p = Objects.requireNonNull(read(id));
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("leg.") && key.endsWith(".status") && "IN_FLIGHT".equals(p.getProperty(key))) {
                throw new IllegalStateException("Cannot close an unknown payment: " + id);
            }
        }
        try {
            Files.move(pending.resolve(id + ".properties"), receipts.resolve(id + ".properties"),
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public synchronized Map<UUID, Properties> pending() {
        Map<UUID, Properties> out = new LinkedHashMap<>();
        try (var files = Files.list(pending)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".properties")).sorted().toList()) {
                UUID id = UUID.fromString(file.getFileName().toString().replace(".properties", ""));
                Properties p = read(id);
                UUID.fromString(p.getProperty("owner"));
                if (!p.getProperty("other", "").isEmpty()) UUID.fromString(p.getProperty("other"));
                out.put(id, p);
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return out;
    }

    public synchronized Properties read(UUID id) {
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

    private void write(UUID id, Properties p) {
        Path temporary = null;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            p.store(bytes, "Royal payment receipt - do not replay IN_FLIGHT automatically");
            temporary = Files.createTempFile(pending, ".payment-", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, pending.resolve(id + ".properties"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* harmless temp only */ }
            }
        }
    }
}
