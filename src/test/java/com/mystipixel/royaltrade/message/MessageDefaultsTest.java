package com.mystipixel.royaltrade.message;

import com.mystipixel.royaltrade.util.Bundled;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Every message key the code reads must ship in messages.yml, and every shipped key must be read.
class MessageDefaultsTest {

    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final Pattern MESSAGE_CALL = Pattern.compile("\\bmessages(?:\\(\\))?\\s*\\.\\s*(send|raw)\\s*\\(");
    private static final Pattern LABEL_CALL = Pattern.compile("\\blabel\\(\"([^\"]+)\",\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\)");

    // keys chosen in code and passed through a variable to send()
    private static final List<String> INDIRECT_KEYS = List.of("toggle-on", "toggle-off", "too-new",
            "they-are-too-new", "not-enough-money", "full-inventory", "economy-error", "other-left",
            "trade-restricted", "cancelled");

    @Test
    void everyKeyReadInCodeShips() throws IOException {
        YamlConfiguration shipped = Bundled.yaml("messages.yml");
        String sources = allSources();
        List<String> problems = new ArrayList<>();
        int literal = 0;
        Matcher m = MESSAGE_CALL.matcher(sources);
        while (m.find()) {
            List<String> args = arguments(sources, m.end());
            String keyArg = args.get(m.group(1).equals("send") ? 1 : 0);
            if (!keyArg.startsWith("\"")) {
                continue;                        // covered by INDIRECT_KEYS
            }
            literal++;
            String key = keyArg.substring(1, keyArg.length() - 1);
            if (!shipped.isString(key)) {
                problems.add(key + " is read but not in messages.yml");
            }
        }
        assertTrue(literal > 40, "found only " + literal + " message reads; is the scan still matching?");
        for (String key : INDIRECT_KEYS) {
            if (!shipped.isString(key) || !sources.contains("\"" + key + "\"")) {
                problems.add(key + " is listed as read indirectly but is not shipped or not in the code");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void dialogButtonFallbacksMatchTheShippedText() throws IOException {
        YamlConfiguration shipped = Bundled.yaml("messages.yml");
        Matcher m = LABEL_CALL.matcher(allSources());
        int found = 0;
        while (m.find()) {
            found++;
            assertEquals(shipped.getString(m.group(1)), m.group(2), m.group(1) + " fallback");
        }
        assertEquals(2, found, "dialog button labels");
    }

    @Test
    void everyShippedKeyIsRead() throws IOException {
        YamlConfiguration shipped = Bundled.yaml("messages.yml");
        String sources = allSources();
        List<String> unused = new ArrayList<>();
        for (String key : shipped.getKeys(true)) {
            if (shipped.isString(key) && !sources.contains("\"" + key + "\"")) {
                unused.add(key);
            }
        }
        assertTrue(unused.isEmpty(), "keys in messages.yml that no code reads: " + unused);
    }

    @Test
    void bundledFilesHaveNoDuplicateKeys() throws IOException {
        for (String file : List.of("messages.yml", "config.yml")) {
            List<String> duplicates = duplicateKeys(resource(file));
            assertTrue(duplicates.isEmpty(), "duplicate keys in " + file + ": " + duplicates);
        }
    }

    // the move must not change what players see
    @Test
    void wordingIsTheSameAsBeforeTheMove() {
        YamlConfiguration shipped = Bundled.yaml("messages.yml");
        assertEquals("&6Trade &8» &r", shipped.getString("prefix"));
        assertEquals("&f{player} &awants to trade. &7Type &f/trade {player} &7to accept.",
                shipped.getString("request-received"));
        assertEquals("&cThis trade is on hold because a payment could not be confirmed. Contact staff; "
                + "do not repeat the trade.", shipped.getString("payment-recovery"));
        assertEquals("&aYou were holding items in a trade when the server stopped. They are back in your "
                + "inventory.", shipped.getString("returned-items"));
        assertEquals("&7Leave it blank to keep the current offer.", shipped.getString("coins-prompt"));
        assertEquals("&aDone", shipped.getString("input.confirm"));
        assertEquals(46, shipped.getKeys(true).stream().filter(shipped::isString).count());
    }

    @Test
    void aFreshServerGetsTheBundledFile(@TempDir Path dir) throws IOException {
        MessageManager messages = new MessageManager(dir.toFile(), Logger.getAnonymousLogger());
        assertEquals(resource("messages.yml"), Files.readString(dir.resolve("messages.yml")));
        assertEquals("&aTrade complete.", messages.raw("completed"));
        assertEquals("no-such-key", messages.raw("no-such-key"));
    }

    @Test
    void anOlderFileFallsBackToTheBundledTextWithoutBeingRewritten(@TempDir Path dir) throws IOException {
        String old = """
                completed: "&bDone!"
                locked: ""
                coins-prompt: "&7Type an amount on the sign, or leave it blank to keep the current offer."
                """;
        Files.writeString(dir.resolve("messages.yml"), old);
        MessageManager messages = new MessageManager(dir.toFile(), Logger.getAnonymousLogger());

        assertEquals("&bDone!", messages.raw("completed"));
        assertEquals("", messages.raw("locked"));
        assertEquals("&cOne of you cannot cover the coins offered.", messages.raw("not-enough-money"));
        assertEquals("&aDone", messages.raw("input.confirm"));
        assertEquals("&7Leave it blank to keep the current offer.", messages.raw("coins-prompt"));
        assertEquals(old, Files.readString(dir.resolve("messages.yml")));
    }

    @Test
    void aCustomisedCoinPromptIsKept(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("messages.yml"), "coins-prompt: \"&7Type an amount on the sign.\"\n");
        MessageManager messages = new MessageManager(dir.toFile(), Logger.getAnonymousLogger());
        assertEquals("&7Type an amount on the sign.", messages.raw("coins-prompt"));
    }

    // YAML keeps only the last of two equal keys, silently dropping the first
    private static List<String> duplicateKeys(String yaml) {
        List<String> duplicates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> path = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        for (String line : yaml.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0 || trimmed.startsWith("\"")) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                path.remove(path.size() - 1);
            }
            path.add(trimmed.substring(0, colon));
            indents.add(indent);
            if (!seen.add(String.join(".", path))) {
                duplicates.add(String.join(".", path));
            }
        }
        return duplicates;
    }

    private static String allSources() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                all.append(Files.readString(file)).append('\n');
            }
        }
        return all.toString();
    }

    // top-level arguments of the call whose '(' ends just before start
    private static List<String> arguments(String source, int start) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"') {
                int end = i + 1;
                while (source.charAt(end) != '"') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                current.append(source, i, end + 1);
                i = end;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    args.add(current.toString().strip());
                    return args;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(current.toString().strip());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        throw new IllegalStateException("unclosed call");
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = MessageDefaultsTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name + " is not bundled");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
