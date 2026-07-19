package com.mystipixel.royaltrade.message;

import com.mystipixel.royaltrade.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every user-facing string, in messages.yml.
 *
 * <p>Defaults live here rather than only in the shipped file, so a message added in a later version
 * appears for servers whose messages.yml predates it instead of rendering as a missing key.
 */
public final class MessageManager {

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("prefix", "&6Trade &8» &r");
        DEFAULTS.put("player-only", "&cOnly players can trade.");
        DEFAULTS.put("usage", "&7Usage: &f/trade <player>");
        DEFAULTS.put("no-such-player", "&cThat player is not online.");
        DEFAULTS.put("cannot-trade-self", "&cYou cannot trade with yourself.");
        DEFAULTS.put("already-trading", "&cYou are already in a trade.");
        DEFAULTS.put("they-are-trading", "&c{player} is already in a trade.");
        DEFAULTS.put("too-far", "&c{player} is too far away.");
        DEFAULTS.put("different-world", "&c{player} is in another world.");
        DEFAULTS.put("too-new", "&cYou need to have played for {hours}h before you can trade.");
        DEFAULTS.put("they-are-too-new", "&c{player} has not played long enough to trade yet.");
        DEFAULTS.put("request-sent", "&aTrade request sent to &f{player}&a.");
        DEFAULTS.put("request-received",
                "&f{player} &awants to trade. &7Type &f/trade {player} &7to accept.");
        DEFAULTS.put("request-cooldown", "&cWait a moment before sending another request.");
        DEFAULTS.put("opened", "&aTrading with &f{player}&a.");
        DEFAULTS.put("cancelled", "&cTrade cancelled. Your items have been returned.");
        DEFAULTS.put("cancelled-by-other", "&c{player} cancelled the trade.");
        DEFAULTS.put("completed", "&aTrade complete.");
        DEFAULTS.put("confirm-reset",
                "&eThe offer changed, so both confirmations were cleared.");
        DEFAULTS.put("locked", "&cThe trade is settling and cannot be changed.");
        DEFAULTS.put("nothing-offered", "&cNeither of you has offered anything.");
        DEFAULTS.put("full-inventory", "&cThere is not enough room in one of your inventories.");
        DEFAULTS.put("not-enough-money", "&cOne of you cannot cover the coins offered.");
        DEFAULTS.put("economy-error", "&cThe payment failed. Nothing was traded.");
        DEFAULTS.put("other-left", "&cThe other player left. The trade was cancelled.");
        DEFAULTS.put("coins-prompt",
                "&7Type an amount in chat, or &fcancel&7 to leave it as it is.");
        DEFAULTS.put("coins-invalid", "&cThat is not a valid amount.");
        DEFAULTS.put("coins-too-much", "&cYou do not have that much.");
        DEFAULTS.put("coins-set", "&aYour coin offer is now &f{amount}&a.");
        DEFAULTS.put("no-economy", "&cNo economy is installed, so coins cannot be traded.");
        DEFAULTS.put("returned-items",
                "&aYou were holding items in a trade when the server stopped. They are back in your "
                        + "inventory.");
        DEFAULTS.put("returned-partial",
                "&eSome items from an interrupted trade did not fit. Make room and rejoin.");
        DEFAULTS.put("reloaded", "&aRoyalTrade reloaded.");
    }

    private final File file;
    private final Logger logger;
    private YamlConfiguration config;

    public MessageManager(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "messages.yml");
        this.logger = logger;
        reload();
    }

    public void reload() {
        config = YamlConfiguration.loadConfiguration(file);
        boolean dirty = false;
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            if (!config.isSet(entry.getKey())) {
                config.set(entry.getKey(), entry.getValue());
                dirty = true;
            }
        }
        if (dirty) {
            try {
                config.save(file);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not write messages.yml", e);
            }
        }
    }

    public String raw(String key) {
        return config.getString(key, DEFAULTS.getOrDefault(key, key));
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
