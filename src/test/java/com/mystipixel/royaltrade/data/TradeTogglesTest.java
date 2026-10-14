package com.mystipixel.royaltrade.data;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class TradeTogglesTest {
    @TempDir Path folder;

    @Test void togglesSurviveARestartAndLeaveNoTempFiles() throws Exception {
        UUID player = UUID.randomUUID();
        TradeToggles toggles = new TradeToggles(folder.toFile(), Logger.getLogger("test"));
        assertTrue(toggles.toggle(player));
        assertFalse(toggles.toggle(player));
        assertTrue(toggles.toggle(player));
        toggles.close();
        assertTrue(new TradeToggles(folder.toFile(), Logger.getLogger("test")).isBlocking(player));
        try (var files = Files.list(folder)) {
            assertEquals(1, files.count());
        }
    }
}
