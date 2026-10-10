package com.mystipixel.royaltrade.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps escrowed items alive across a crash. Every change is written to disk synchronously and
 * removed only once the items are back in an inventory. Anything left at boot is moved to
 * {@code pending} and handed back on each owner's next join.
 */
public final class Escrow {

    private static final String ESCROW = "escrow";
    private static final String PENDING = "pending";

    private final File file;
    private final Logger logger;
    private YamlConfiguration data;

    public Escrow(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "escrow.yml");
        this.logger = logger;
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
        }
        return recovered;
    }

    /** Record (or replace) what each player has escrowed in one session, in one write. */
    public void hold(UUID sessionId, Map<UUID, List<ItemStack>> offers) {
        for (Map.Entry<UUID, List<ItemStack>> offer : offers.entrySet()) {
            String path = ESCROW + "." + sessionId + "." + offer.getKey();
            data.set(path, offer.getValue().isEmpty() ? null : new ArrayList<>(offer.getValue()));
        }
        save();
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

    private void save() {
        java.nio.file.Path temporary = null;
        try {
            java.nio.file.Path target = file.toPath();
            temporary = java.nio.file.Files.createTempFile(target.getParent(), ".escrow-", ".tmp");
            byte[] bytes = data.saveToString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            java.nio.file.Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Cannot persist escrow; stopping this operation", e);
            throw new java.io.UncheckedIOException(e);
        } finally {
            if (temporary != null) {
                try { java.nio.file.Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }
}
