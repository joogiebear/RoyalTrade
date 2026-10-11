package com.mystipixel.royaltrade.gui;

import com.mystipixel.royaltrade.util.Bundled;
import com.mystipixel.royaltrade.util.Text;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The layout and text of the trade window, from {@code gui/trade.yml}. A button or state missing
 * from the server's file falls back to the jar's copy.
 */
public final class TradeMenu {

    public static final String MY_COINS = "my-coins";
    public static final String THEIR_COINS = "their-coins";
    public static final String MY_CONFIRM = "my-confirm";
    public static final String THEIR_CONFIRM = "their-confirm";
    public static final String CANCEL = "cancel";

    // a button with one look has its item at the slot itself, read as this state
    static final String PLAIN = "plain";

    static final Map<String, List<String>> BUTTONS = buttons();

    private static Map<String, List<String>> buttons() {
        Map<String, List<String>> buttons = new LinkedHashMap<>();
        buttons.put(MY_COINS, List.of("none", "some"));
        buttons.put(THEIR_COINS, List.of("none", "some"));
        buttons.put(MY_CONFIRM, List.of("unconfirmed", "confirmed", "waiting", "settling"));
        buttons.put(THEIR_CONFIRM, List.of("unconfirmed", "confirmed", "settling"));
        buttons.put(CANCEL, List.of(PLAIN));
        return buttons;
    }

    private static final Pattern NAME = Pattern.compile("name:\"(.*)\"");

    /** One item's look before placeholders are filled; {@code name} null keeps the vanilla name. */
    public record Look(Material material, String name, List<String> lore) {

        /** Placeholders filled, then colour codes translated. */
        public Look fill(Map<String, String> placeholders) {
            List<String> lines = new ArrayList<>(lore.size());
            for (String line : lore) {
                lines.add(Text.color(apply(line, placeholders)));
            }
            return new Look(material, name == null ? null : Text.color(apply(name, placeholders)), lines);
        }
    }

    private final String title;
    private final int size;
    private final Look[] mask;
    private final int[] myOffer;
    private final int[] theirOffer;
    private final Map<String, Integer> slots;
    private final Map<String, Map<String, Look>> looks;

    private TradeMenu(String title, int size, Look[] mask, int[] myOffer, int[] theirOffer,
                      Map<String, Integer> slots, Map<String, Map<String, Look>> looks) {
        this.title = title;
        this.size = size;
        this.mask = mask;
        this.myOffer = myOffer;
        this.theirOffer = theirOffer;
        this.slots = slots;
        this.looks = looks;
    }

    /** Copies the jar's gui/trade.yml out if the server has none, then reads the server's file. */
    public static TradeMenu load(File dataFolder, Logger logger) {
        File file = new File(dataFolder, "gui/trade.yml");
        try {
            Bundled.copyIfMissing("gui/trade.yml", file);
        } catch (java.io.IOException e) {
            logger.warning("Could not write gui/trade.yml; using the built-in trade window: " + e.getMessage());
        }
        return parse(YamlConfiguration.loadConfiguration(file), Bundled.yaml("gui/trade.yml"), logger);
    }

    static TradeMenu parse(YamlConfiguration server, YamlConfiguration bundled, Logger logger) {
        server.setDefaults(bundled);
        int rows = Math.max(1, Math.min(6, server.getInt("rows")));
        int size = rows * 9;

        Look[] mask = new Look[size];
        Map<Character, List<Integer>> byChar = new HashMap<>();
        List<String> items = server.getStringList("mask.items");
        List<String> pattern = server.getStringList("mask.pattern");
        for (int r = 0; r < rows && r < pattern.size(); r++) {
            String line = pattern.get(r);
            for (int c = 0; c < 9 && c < line.length(); c++) {
                int index = r * 9 + c;
                char ch = line.charAt(c);
                byChar.computeIfAbsent(ch, k -> new ArrayList<>()).add(index);
                if (ch >= '1' && ch <= '9' && ch - '1' < items.size()) {
                    Look filler = look(items.get(ch - '1'), List.of(), logger);
                    mask[index] = filler.name() == null ? new Look(filler.material(), " ", List.of()) : filler;
                }
            }
        }
        int[] myOffer = region(server, "my-offer", byChar);
        int[] theirOffer = region(server, "their-offer", byChar);
        if (Math.min(myOffer.length, theirOffer.length) == 0) {
            logger.warning("gui/trade.yml: an offer area is empty, so no items can be traded.");
        }

        Map<String, Map<?, ?>> serverSlots = slotsById(server.getMapList("slots"));
        Map<String, Map<?, ?>> bundledSlots = slotsById(bundled.getMapList("slots"));
        Map<String, Integer> slots = new HashMap<>();
        Map<String, Map<String, Look>> looks = new HashMap<>();
        for (Map.Entry<String, List<String>> button : BUTTONS.entrySet()) {
            String id = button.getKey();
            Map<?, ?> own = serverSlots.get(id);
            Map<?, ?> fallback = bundledSlots.get(id);
            int index = slotIndex(own != null && own.containsKey("row") ? own : fallback);
            if (index < 0 || index >= size) {
                logger.warning("gui/trade.yml: button " + id + " is outside the window and is not shown.");
            } else {
                slots.put(id, index);
            }
            Map<String, Look> states = new HashMap<>();
            for (String state : button.getValue()) {
                Map<?, ?> spec = stateOf(own, state);
                if (spec == null) {
                    spec = stateOf(fallback, state);
                }
                states.put(state, look(String.valueOf(spec.get("item")), stringList(spec.get("lore")), logger));
            }
            looks.put(id, states);
        }
        return new TradeMenu(String.valueOf(server.getString("title")), size, mask, myOffer, theirOffer, slots, looks);
    }

    public String title(Map<String, String> placeholders) {
        return Text.color(apply(title, placeholders));
    }

    public int size() {
        return size;
    }

    /** The filler at {@code index}, or null for an empty slot. */
    public Look maskAt(int index) {
        return index < mask.length ? mask[index] : null;
    }

    public int[] myOffer() {
        return myOffer.clone();
    }

    public int[] theirOffer() {
        return theirOffer.clone();
    }

    /** Stacks one side may offer: both windows must be able to show them. */
    public int offerCapacity() {
        return Math.min(myOffer.length, theirOffer.length);
    }

    /** Which index of the viewer's offer a slot shows, or -1. */
    public int myOfferIndex(int slot) {
        for (int i = 0; i < offerCapacity(); i++) {
            if (myOffer[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    /** Slot of a button, or -1 when it is not shown. */
    public int slotOf(String button) {
        return slots.getOrDefault(button, -1);
    }

    /** The button at a slot, or null. */
    public String buttonAt(int slot) {
        for (String id : BUTTONS.keySet()) {
            if (slotOf(id) == slot) {
                return id;
            }
        }
        return null;
    }

    public Look look(String button, String state) {
        return looks.get(button).get(state);
    }

    private static int[] region(YamlConfiguration cfg, String name, Map<Character, List<Integer>> byChar) {
        String marker = cfg.getString("regions." + name);    // one-arg getString: falls back to the jar's file
        if (marker == null || marker.isEmpty()) {
            return new int[0];
        }
        return byChar.getOrDefault(marker.charAt(0), List.of()).stream().mapToInt(Integer::intValue).toArray();
    }

    private static Map<String, Map<?, ?>> slotsById(List<Map<?, ?>> raw) {
        Map<String, Map<?, ?>> byId = new HashMap<>();
        for (Map<?, ?> slot : raw) {
            if (slot.get("id") != null) {
                byId.put(String.valueOf(slot.get("id")), slot);
            }
        }
        return byId;
    }

    private static Map<?, ?> stateOf(Map<?, ?> slot, String state) {
        if (slot == null) {
            return null;
        }
        if (PLAIN.equals(state)) {
            return slot.get("item") != null ? slot : null;
        }
        if (slot.get("states") instanceof Map<?, ?> states && states.get(state) instanceof Map<?, ?> spec
                && spec.get("item") != null) {
            return spec;
        }
        return null;
    }

    private static int slotIndex(Map<?, ?> slot) {
        if (!(slot.get("row") instanceof Number row) || !(slot.get("column") instanceof Number column)) {
            return -1;
        }
        if (column.intValue() < 1 || column.intValue() > 9) {
            return -1;
        }
        return (row.intValue() - 1) * 9 + (column.intValue() - 1);
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object line : list) {
                out.add(String.valueOf(line));
            }
        } else if (raw != null) {
            out.add(String.valueOf(raw));
        }
        return out;
    }

    // material name:"display name"
    static Look look(String spec, List<String> lore, Logger logger) {
        String trimmed = spec.strip();
        int space = trimmed.indexOf(' ');
        String materialName = space < 0 ? trimmed : trimmed.substring(0, space);
        Material material = Material.matchMaterial(materialName);
        if (material == null || material == Material.AIR) {
            logger.warning("gui/trade.yml: unknown item " + materialName + ", showing stone instead.");
            material = Material.STONE;
        }
        String name = null;
        if (space >= 0) {
            Matcher m = NAME.matcher(trimmed.substring(space + 1));
            if (m.find()) {
                name = m.group(1);
            }
        }
        return new Look(material, name, List.copyOf(lore));
    }

    static String apply(String text, Map<String, String> placeholders) {
        String out = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            out = out.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return out;
    }
}
