package com.mystipixel.royaltrade.data;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Who has incoming trade requests switched off, persisted in {@code toggles.yml}. */
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

    private void save() {
        YamlConfiguration out = new YamlConfiguration();
        List<String> ids = new ArrayList<>(blocking.size());
        for (UUID id : blocking) {
            ids.add(id.toString());
        }
        out.set("blocking", ids);
        // temp file then move, so a crash mid-write keeps the old list instead of an empty one
        Path temporary = null;
        try {
            Path target = file.toPath();
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".toggles-", ".tmp");
            Files.writeString(temporary, out.saveToString(), StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not save toggles.yml; request toggles may reset on restart.", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // a stray temp file is harmless
                }
            }
        }
    }
}
