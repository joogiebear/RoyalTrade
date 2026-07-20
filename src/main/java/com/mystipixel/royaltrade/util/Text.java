package com.mystipixel.royaltrade.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

/** Colour codes, and the one piece of formatting every item in this plugin needs. */
public final class Text {

    private Text() {
    }

    public static String color(String input) {
        return input == null ? "" : ChatColor.translateAlternateColorCodes('&', input);
    }

    /** The same string as a Component, for the APIs that no longer take legacy strings. */
    public static Component chat(String input) {
        return LegacyComponentSerializer.legacySection().deserialize(color(input));
    }
}
