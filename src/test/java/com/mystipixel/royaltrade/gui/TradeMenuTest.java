package com.mystipixel.royaltrade.gui;

import com.mystipixel.royaltrade.util.Bundled;
import com.mystipixel.royaltrade.util.Text;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeMenuTest {

    private static final Logger LOG = Logger.getAnonymousLogger();

    private static TradeMenu bundled() {
        return TradeMenu.parse(Bundled.yaml("gui/trade.yml"), Bundled.yaml("gui/trade.yml"), LOG);
    }

    @Test
    void theShippedFileDefinesEveryButtonAndState() {
        YamlConfiguration yaml = Bundled.yaml("gui/trade.yml");
        Map<String, Map<?, ?>> byId = new HashMap<>();
        for (Map<?, ?> slot : yaml.getMapList("slots")) {
            assertNull(byId.put(String.valueOf(slot.get("id")), slot), "two slots with id " + slot.get("id"));
        }
        TradeMenu.BUTTONS.forEach((id, states) -> {
            Map<?, ?> slot = byId.get(id);
            assertNotNull(slot, id);
            for (String state : states) {
                Object spec = TradeMenu.PLAIN.equals(state) ? slot
                        : ((Map<?, ?>) slot.get("states")).get(state);
                assertTrue(spec instanceof Map<?, ?> m && m.get("item") != null, id + " state " + state);
            }
        });
    }

    @Test
    void noTwoButtonsShareASlotOrSitOnAnOffer() {
        TradeMenu menu = bundled();
        List<Integer> taken = new ArrayList<>();
        for (int slot : menu.myOffer()) {
            taken.add(slot);
        }
        for (int slot : menu.theirOffer()) {
            taken.add(slot);
        }
        for (String id : TradeMenu.BUTTONS.keySet()) {
            int slot = menu.slotOf(id);
            assertTrue(slot >= 0 && slot < menu.size(), id + " is inside the window");
            assertTrue(!taken.contains(slot), id + " shares slot " + slot);
            taken.add(slot);
        }
    }

    // the slots, filler and offer areas the window had when they were constants in TradeGui
    @Test
    void layoutIsTheSameAsBeforeTheMove() {
        TradeMenu menu = bundled();
        assertEquals(54, menu.size());
        assertArrayEquals(new int[]{0, 1, 2, 3, 9, 10, 11, 12, 18, 19, 20, 21, 27, 28, 29, 30}, menu.myOffer());
        assertArrayEquals(new int[]{5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26, 32, 33, 34, 35}, menu.theirOffer());
        assertEquals(16, menu.offerCapacity());
        assertEquals(37, menu.slotOf(TradeMenu.MY_COINS));
        assertEquals(43, menu.slotOf(TradeMenu.THEIR_COINS));
        assertEquals(47, menu.slotOf(TradeMenu.MY_CONFIRM));
        assertEquals(51, menu.slotOf(TradeMenu.THEIR_CONFIRM));
        assertEquals(45, menu.slotOf(TradeMenu.CANCEL));
        assertEquals(TradeMenu.CANCEL, menu.buttonAt(45));
        assertNull(menu.buttonAt(44));
        assertEquals(7, menu.myOfferIndex(12));
        assertEquals(-1, menu.myOfferIndex(5));

        List<Integer> divider = List.of(4, 13, 22, 31, 40, 49);
        List<Integer> offers = new ArrayList<>();
        Arrays.stream(menu.myOffer()).forEach(offers::add);
        Arrays.stream(menu.theirOffer()).forEach(offers::add);
        for (int i = 0; i < 54; i++) {
            TradeMenu.Look filler = menu.maskAt(i);
            if (offers.contains(i)) {
                assertNull(filler, "slot " + i);
            } else {
                Material expected = divider.contains(i) ? Material.GRAY_STAINED_GLASS_PANE
                        : Material.BLACK_STAINED_GLASS_PANE;
                assertEquals(new TradeMenu.Look(expected, " ", List.of()), filler.fill(Map.of()), "slot " + i);
            }
        }
    }

    @Test
    void itemsAreTheSameAsBeforeTheMove() {
        TradeMenu menu = bundled();
        assertEquals(Text.color("&8Trading with &fSteve"), menu.title(Map.of("player", "Steve")));

        Map<String, String> coins = Map.of("amount", "$1,000.00");
        expect(menu, TradeMenu.MY_COINS, "some", coins, Material.GOLD_INGOT, "&eYour offer",
                "&f$1,000.00", "", "&eClick to change", "&8Changing this clears both confirmations.");
        expect(menu, TradeMenu.MY_COINS, "none", coins, Material.GOLD_NUGGET, "&eYour offer",
                "&f$1,000.00", "", "&eClick to change", "&8Changing this clears both confirmations.");
        expect(menu, TradeMenu.THEIR_COINS, "some", coins, Material.GOLD_INGOT, "&eTheir offer",
                "&f$1,000.00", "", "&8What they are putting in.");
        expect(menu, TradeMenu.THEIR_COINS, "none", coins, Material.GOLD_NUGGET, "&eTheir offer",
                "&f$1,000.00", "", "&8What they are putting in.");

        Map<String, String> seconds = Map.of("seconds", "3");
        for (String button : List.of(TradeMenu.MY_CONFIRM, TradeMenu.THEIR_CONFIRM)) {
            expect(menu, button, "settling", seconds, Material.CLOCK, "&6Completing in 3s…",
                    "&7Both sides have confirmed.", "&7The trade is locked while it settles.");
        }
        expect(menu, TradeMenu.MY_CONFIRM, "waiting", seconds, Material.ORANGE_DYE,
                "&6Offer changed: confirm in 3s", "&7Check both sides before you confirm.");
        expect(menu, TradeMenu.MY_CONFIRM, "confirmed", Map.of(), Material.LIME_DYE, "&aYou have confirmed",
                "&7Waiting for the other player.", "", "&eClick to take it back");
        expect(menu, TradeMenu.MY_CONFIRM, "unconfirmed", Map.of(), Material.GRAY_DYE, "&7Confirm trade",
                "&7Check the right-hand side first.", "", "&eClick to confirm");
        expect(menu, TradeMenu.THEIR_CONFIRM, "confirmed", Map.of(), Material.LIME_DYE, "&aThey have confirmed",
                "&7Waiting on you.");
        expect(menu, TradeMenu.THEIR_CONFIRM, "unconfirmed", Map.of(), Material.GRAY_DYE,
                "&7They have not confirmed", "&7Give them a moment.");
        expect(menu, TradeMenu.CANCEL, TradeMenu.PLAIN, Map.of(), Material.BARRIER, "&cCancel trade",
                "&7Nothing changes hands.", "&7Your items come straight back.");
    }

    @Test
    void aServerFileOverridesWhatItHasAndFallsBackForTheRest() throws InvalidConfigurationException {
        YamlConfiguration server = new YamlConfiguration();
        server.loadFromString("""
                slots:
                  - id: cancel
                    row: 6
                    column: 9
                    item: red_wool name:"&cNo thanks"
                  - id: my-confirm
                    states:
                      confirmed:
                        item: emerald name:"&aReady"
                """);
        TradeMenu menu = TradeMenu.parse(server, Bundled.yaml("gui/trade.yml"), LOG);

        assertEquals(Text.color("&8Trading with &fAlex"), menu.title(Map.of("player", "Alex")));
        assertEquals(53, menu.slotOf(TradeMenu.CANCEL));
        assertEquals(new TradeMenu.Look(Material.RED_WOOL, "&cNo thanks", List.of()),
                menu.look(TradeMenu.CANCEL, TradeMenu.PLAIN));
        assertEquals(47, menu.slotOf(TradeMenu.MY_CONFIRM));
        assertEquals(Material.EMERALD, menu.look(TradeMenu.MY_CONFIRM, "confirmed").material());
        assertEquals("&7Confirm trade", menu.look(TradeMenu.MY_CONFIRM, "unconfirmed").name());
        assertEquals(37, menu.slotOf(TradeMenu.MY_COINS));
        assertEquals(16, menu.offerCapacity());
    }

    @Test
    void anUnknownMaterialShowsStone() {
        assertEquals(Material.STONE, TradeMenu.look("not_a_block name:\"x\"", List.of(), LOG).material());
    }

    private static void expect(TradeMenu menu, String button, String state, Map<String, String> placeholders,
                               Material material, String name, String... lore) {
        List<String> coloured = new ArrayList<>();
        for (String line : lore) {
            coloured.add(Text.color(line));
        }
        assertEquals(new TradeMenu.Look(material, Text.color(name), coloured),
                menu.look(button, state).fill(placeholders), button + " " + state);
    }
}
