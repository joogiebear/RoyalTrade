package com.mystipixel.royaltrade.data;

import com.mystipixel.royaltrade.util.BackgroundWriter;
import com.mystipixel.royaltrade.util.DataFiles;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Who has incoming trade requests switched off, persisted in {@code toggles.yml}. */
public final class TradeToggles {

    private static final long CLOSE_TIMEOUT_MILLIS = 5_000L;

    private final File file;
    private final Logger logger;
    private final Set<UUID> blocking = ConcurrentHashMap.newKeySet();
    private final BackgroundWriter<String> writer;

    public TradeToggles(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "toggles.yml");
        this.logger = logger;
        // temp file then move, so a crash mid-write keeps the old list instead of an empty one
        this.writer = new BackgroundWriter<>("toggles.yml", "RoyalTrade-toggles-writer",
                snapshot -> DataFiles.write(file.toPath(), snapshot), (queued, newer) -> newer, logger);
        load();
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        for (String raw : YamlConfiguration.loadConfiguration(file).getStringList("blocking")) {
            try {
                blocking.add(UUID.fromString(raw));
            } catch (IllegalArgumentException notAUuid) {
                logger.warning("toggles.yml has an entry that isn't a player id: " + raw);
            }
        }
    }

    public boolean isBlocking(UUID player) {
        return blocking.contains(player);
    }

    /** Flip the player's setting and persist it. Returns true if now blocking. */
    public boolean toggle(UUID player) {
        boolean nowBlocking;
        if (blocking.remove(player)) {
            nowBlocking = false;
        } else {
            blocking.add(player);
            nowBlocking = true;
        }
        save();
        return nowBlocking;
    }

    // serialised on the caller's thread, written on the writer thread; only the newest list matters
    private void save() {
        YamlConfiguration out = new YamlConfiguration();
        List<String> ids = new ArrayList<>(blocking.size());
        for (UUID id : blocking) {
            ids.add(id.toString());
        }
        out.set("blocking", ids);
        writer.submit(out.saveToString());
    }

    /** Waits for the last toggle to reach disk. Call from onDisable. */
    public void close() {
        writer.close(CLOSE_TIMEOUT_MILLIS);
    }
}
