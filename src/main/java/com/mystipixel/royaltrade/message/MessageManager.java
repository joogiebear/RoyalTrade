package com.mystipixel.royaltrade.message;

import com.mystipixel.royaltrade.util.Bundled;
import com.mystipixel.royaltrade.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Player-facing text from {@code messages.yml}. The jar's copy backs every key the server's file
 * lacks; the server's file is never rewritten.
 */
public final class MessageManager {

    // wording shipped by earlier versions; a file still holding it shows today's default instead
    private static final Map<String, String> RETIRED = Map.of(
            "coins-prompt", "&7Type an amount on the sign, or leave it blank to keep the current offer.");

    private final File file;
    private final Logger logger;
    private final YamlConfiguration defaults = Bundled.yaml("messages.yml");
    private YamlConfiguration config;

    public MessageManager(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "messages.yml");
        this.logger = logger;
        reload();
    }

    public void reload() {
        try {
            Bundled.copyIfMissing("messages.yml", file);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not write messages.yml; using the built-in messages", e);
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        loaded.setDefaults(defaults);
        config = loaded;
    }

    public String raw(String key) {
        String value = config.getString(key);
        if (value == null) {
            return key;
        }
        return value.equals(RETIRED.get(key)) ? defaults.getString(key, value) : value;
    }

    public void send(CommandSender to, String key) {
        send(to, key, Map.of());
    }

    public void send(CommandSender to, String key, Map<String, String> placeholders) {
        String message = raw(key);
        if (message.isEmpty()) {
            return;                              // blanked out in config = deliberately silent
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        to.sendMessage(Text.color(raw("prefix") + message));
    }
}
