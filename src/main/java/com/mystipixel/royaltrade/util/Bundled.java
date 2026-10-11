package com.mystipixel.royaltrade.util;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Files shipped inside the jar: read as defaults, and copied out once for owners to edit. */
public final class Bundled {

    private Bundled() {
    }

    public static YamlConfiguration yaml(String path) {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = open(path)) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path + " from the jar", e);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException(path + " in the jar is not valid YAML", e);
        }
        return yaml;
    }

    /** Writes the jar's copy to {@code target} unless a file is already there. */
    public static void copyIfMissing(String path, File target) throws IOException {
        if (target.exists()) {
            return;
        }
        Files.createDirectories(target.getAbsoluteFile().getParentFile().toPath());
        try (InputStream in = open(path)) {
            Files.copy(in, target.toPath());
        }
    }

    // uncached, like JavaPlugin.getResource: a cached jar connection serves the old jar after /reload
    private static InputStream open(String path) throws IOException {
        URL url = Bundled.class.getClassLoader().getResource(path);
        if (url == null) {
            throw new IOException(path + " is missing from the jar");
        }
        URLConnection connection = url.openConnection();
        connection.setUseCaches(false);
        return connection.getInputStream();
    }
}
