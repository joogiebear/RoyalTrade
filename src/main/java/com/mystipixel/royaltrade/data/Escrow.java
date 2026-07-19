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
 * Keeps escrowed items alive across a crash.
 *
 * <p>While a trade is open the offered items are not in anyone's inventory — they are held by the
 * session. If the server dies at that moment, those items exist only in memory and are simply gone,
 * which reaches the owner as "your plugin ate my hotbar". So every change to escrow is written to
 * disk immediately, and the record is deleted only once the items are somewhere real again.
 *
 * <p>On boot anything still on disk is proof of an unclean shutdown: those items are moved to
 * {@code pending} and handed back the next time each owner logs in. Returning them at boot is not an
 * option, because the owner is usually offline.
 *
 * <p>Writes are synchronous. They are small (a few item stacks) and only happen when a human clicks
 * something, so the cost is irrelevant next to losing items — and an async write is exactly the write
 * that has not landed yet when the process dies.
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
     *
     * @return how many players are owed items, for the startup log
     */
    public int load() {
        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            logger.warning("Could not create the data folder — escrow will not survive a restart.");
        }
        data = YamlConfiguration.loadConfiguration(file);

        int recovered = 0;
        if (data.isConfigurationSection(ESCROW)) {
            for (String sessionId : List.copyOf(data.getConfigurationSection(ESCROW).getKeys(false))) {
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
                        queue(UUID.fromString(owner), items);
                        recovered++;
                    }
                }
            }
            data.set(ESCROW, null);
            save();
        }
        return recovered;
    }

    /** Record (or replace) what one player currently has escrowed in one session. */
    public void hold(UUID sessionId, UUID playerId, List<ItemStack> items) {
        String path = ESCROW + "." + sessionId + "." + playerId;
        data.set(path, items.isEmpty() ? null : new ArrayList<>(items));
        save();
    }

    /** Forget a session. Called once its items are back in inventories, either way the trade ended. */
    public void release(UUID sessionId) {
        data.set(ESCROW + "." + sessionId, null);
        save();
    }

    // ------------------------------------------------------------------ pending returns

    /** Owe a player some items. Appends, so two bad shutdowns do not overwrite each other. */
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

    /**
     * Give a player everything they are owed. Anything that will not fit stays owed, so a full
     * inventory delays delivery rather than destroying it.
     *
     * @return how many stacks were handed over
     */
    public int deliver(Player player) {
        List<ItemStack> owed = pendingFor(player.getUniqueId());
        if (owed.isEmpty()) {
            return 0;
        }
        Map<Integer, ItemStack> leftover =
                player.getInventory().addItem(owed.toArray(new ItemStack[0]));
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
        try {
            data.save(file);
        } catch (IOException e) {
            // Worth shouting about: from here on a crash loses whatever is escrowed.
            logger.log(Level.SEVERE, "Could not write escrow.yml — items in open trades are now "
                    + "at risk if the server stops uncleanly.", e);
        }
    }
}
