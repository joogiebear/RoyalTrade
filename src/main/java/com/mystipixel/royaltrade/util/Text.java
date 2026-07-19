package com.mystipixel.royaltrade.util;

import org.bukkit.ChatColor;

/** Colour codes, and the one piece of formatting every item in this plugin needs. */
public final class Text {

    private Text() {
    }

    public static String color(String input) {
        return input == null ? "" : ChatColor.translateAlternateColorCodes('&', input);
    }
}
