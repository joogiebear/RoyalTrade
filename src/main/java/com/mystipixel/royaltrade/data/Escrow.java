package com.mystipixel.royaltrade.data;

import com.mystipixel.royaltrade.util.BackgroundWriter;
import com.mystipixel.royaltrade.util.DataFiles;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps escrowed items alive across a crash. Changes are written by a background writer, newest state
 * wins; {@link #flush} waits until the current state is on disk. Anything left at boot is moved to
 * {@code pending} and handed back on each owner's next join.
 */
public final class Escrow {

    private static final String ESCROW = "escrow";
    private static final String PENDING = "pending";

    // settlement blocks the main thread on this; a disk slower than this holds the trade instead
    private static final long FLUSH_TIMEOUT_MILLIS = 10_000L;

    private final File file;
    private final Logger logger;
    private final Executor mainThread;
    private final BackgroundWriter<String> writer;
    private YamlConfiguration data;
    // false until load succeeds, so a failed load never writes an empty store over the real one
    private boolean loaded;

    public Escrow(File dataFolder, Logger logger, Executor mainThread) {
        this.file = new File(dataFolder, "escrow.yml");
        this.logger = logger;
        this.mainThread = mainThread;
        this.writer = new BackgroundWriter<>("escrow.yml", "RoyalTrade-escrow-writer",
                snapshot -> DataFiles.write(file.toPath(), snapshot), (queued, newer) -> newer, logger);
    }

    /**
     * Load the store and sweep anything left behind by an unclean shutdown into {@code pending}.
     * Returns how many players are owed items.
     */
    public int load(java.util.Set<UUID> heldSessions) {
        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            logger.warning("Could not create the data folder; escrow will not survive a restart.");
        }
        data = new YamlConfiguration();
        try { if (file.exists()) data.load(file); }
        catch (Exception e) { throw new IllegalStateException("Cannot read escrow safely", e); }

        int recovered = 0;
        if (data.isConfigurationSection(ESCROW)) {
            for (String sessionId : List.copyOf(data.getConfigurationSection(ESCROW).getKeys(false))) {
                if (heldSessions.contains(UUID.fromString(sessionId))) continue;
                String base = ESCROW + "." + sessionId;
                for (String owner : data.getConfigurationSection(base).getKeys(false)) {
                    List<?> raw = data.getList(base + "." + owner, List.of());
                    List<ItemStack> items = new ArrayList<>();
                    for (Object o : raw) {
                        if (o instanceof ItemStack stack) {
                            items.add(stack);
                        }
                    }
                    if (!items.isEmpty()) {
                        List<ItemStack> existing = pendingFor(UUID.fromString(owner));
                        existing.addAll(items);
                        data.set(PENDING + "." + owner, existing);
                        recovered++;
                    }
                }
                data.set(base, null);
            }
            save();
            flush();
        }
        loaded = true;
        return recovered;
    }

    /**
     * Record (or replace) what each player has escrowed in one session, in one write. Returns the write's
     * number for {@link #whenDurable}.
     */
    public long hold(UUID sessionId, Map<UUID, List<ItemStack>> offers) {
        for (Map.Entry<UUID, List<ItemStack>> offer : offers.entrySet()) {
            String path = ESCROW + "." + sessionId + "." + offer.getKey();
            data.set(path, offer.getValue().isEmpty() ? null : new ArrayList<>(offer.getValue()));
        }
        return save();
    }

    /** Forget a session once its items are back in inventories. */
    public void release(UUID sessionId) {
        data.set(ESCROW + "." + sessionId, null);
        save();
    }

    /** Owe a player some items. Appends to what they are already owed. */
    public void queue(UUID playerId, List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        List<ItemStack> existing = pendingFor(playerId);
        existing.addAll(items);
        data.set(PENDING + "." + playerId, existing);
        save();
    }

    public List<ItemStack> pendingFor(UUID playerId) {
        List<ItemStack> out = new ArrayList<>();
        for (Object o : data.getList(PENDING + "." + playerId, List.of())) {
            if (o instanceof ItemStack stack) {
                out.add(stack);
            }
        }
        return out;
    }

    /** Give a player everything they are owed; what doesn't fit stays owed. Returns stacks handed over. */
    public int deliver(Player player) {
        List<ItemStack> owed = pendingFor(player.getUniqueId());
        if (owed.isEmpty()) {
            return 0;
        }
        Map<Integer, ItemStack> leftover =
                player.getInventory().addItem(owed.toArray(new ItemStack[0]));
        // save the inventory before shrinking the debt: a crash in between re-delivers, never loses
        try {
            player.saveData();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not save " + player.getName() + "'s data after "
                    + "returning escrow; a crash before the next autosave may return it again.", e);
        }
        List<ItemStack> still = new ArrayList<>(leftover.values());
        data.set(PENDING + "." + player.getUniqueId(), still.isEmpty() ? null : still);
        save();
        return owed.size() - still.size();
    }

    public Map<UUID, Integer> outstanding() {
        Map<UUID, Integer> out = new LinkedHashMap<>();
        if (!data.isConfigurationSection(PENDING)) {
            return out;
        }
        for (String key : data.getConfigurationSection(PENDING).getKeys(false)) {
            out.put(UUID.fromString(key), data.getList(PENDING + "." + key, List.of()).size());
        }
        return out;
    }

    /** Blocks until everything changed so far is on disk. Throws if it could not be written. */
    public void flush() {
        writer.await(writer.requested(), FLUSH_TIMEOUT_MILLIS);
    }

    /** Runs task on the main thread once write {@code seq} (or a newer one) is on disk. */
    public void whenDurable(long seq, Runnable task) {
        writer.whenDurable(seq, () -> mainThread.execute(task));
    }

    /** True when every change so far is on disk. */
    public boolean isDurable() {
        return writer.durable() >= writer.requested();
    }

    /** The newest write that is on disk. */
    public long durableSeq() {
        return writer.durable();
    }

    /** Writes the final state and stops the writer: the disable path. */
    public void close() {
        if (loaded) {
            try {
                writer.await(save(), FLUSH_TIMEOUT_MILLIS);
            } catch (UncheckedIOException | IllegalStateException e) {
                logger.log(Level.SEVERE, "Could not write escrow.yml on shutdown; the last escrow change may be lost", e);
            }
        }
        writer.close(FLUSH_TIMEOUT_MILLIS);
    }

    // serialised here on the main thread so the snapshot is consistent; written in the background
    private long save() {
        return writer.submit(data.saveToString());
    }
}
