package com.mystipixel.royaltrade.data;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Who has incoming trade requests switched off, persisted in {@code toggles.yml}.
 *
 * <p>Persisted rather than per-session on purpose: an opt-out that silently resets on relog is worse
 * than none, because the player only discovers it via the next unwanted request. Writes are small,
 * synchronous and rare (a human typed a command), following the same reasoning as the escrow store.
 */
public final class TradeToggles {

    private final File file;
    private final Logger logger;
    private final Set<UUID> blocking = ConcurrentHashMap.newKeySet();

    public TradeToggles(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "toggles.yml");
        this.logger = logger;
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

    /** Whether this player has incoming trade requests blocked. */
    public boolean isBlocking(UUID player) {
        return blocking.contains(player);
    }

    /** Flip the player's setting and persist it. Returns the new state — true = now blocking. */
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

    private void save() {
        YamlConfiguration out = new YamlConfiguration();
        List<String> ids = new ArrayList<>(blocking.size());
        for (UUID id : blocking) {
            ids.add(id.toString());
        }
        out.set("blocking", ids);
        try {
            out.save(file);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not save toggles.yml — request toggles may reset on restart.", e);
        }
    }
}
