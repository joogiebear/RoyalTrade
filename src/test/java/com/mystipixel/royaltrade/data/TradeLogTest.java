package com.mystipixel.royaltrade.data;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TradeLogTest {
    @TempDir Path folder;

    private static Player player(String name) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getName()).thenReturn(name);
        return p;
    }

    @Test void amountsUseAPointWhateverTheServerLocale() throws Exception {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            new TradeLog(folder.toFile(), Logger.getLogger("test"))
                    .record(UUID.randomUUID(), player("a"), player("b"), List.of(), List.of(), 1234.5, 0);
        } finally {
            Locale.setDefault(previous);
        }
        String line = Files.readString(folder.resolve("trades.log"));
        assertTrue(line.contains("gave [nothing] + 1234.50"), line);
        assertFalse(line.contains("1234,50"), line);
    }
}
