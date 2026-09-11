package com.mystipixel.royaltrade.data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class EscrowRecoveryTest {
    @TempDir Path root;
    @Test void startupLeavesPaymentHeldSessionInEscrowButSweepsOrdinarySession() throws Exception {
        UUID held = UUID.randomUUID(), ordinary = UUID.randomUUID(), owner = UUID.randomUUID();
        Path folder = Files.createDirectory(root.resolve("data"));
        Path file = folder.resolve("escrow.yml");
        Files.writeString(file, "escrow:\n  " + held + ":\n    " + owner + ": []\n  "
                + ordinary + ":\n    " + owner + ": []\n");
        var store = new Escrow(folder.toFile(), Logger.getLogger("test"));
        assertEquals(0, store.load(Set.of(held)));
        String saved = Files.readString(file);
        assertTrue(saved.contains(held.toString()));
        assertFalse(saved.contains(ordinary.toString()));
    }
    @Test void corruptedEscrowCannotSilentlyLoadAsEmpty() throws Exception {
        Path folder = Files.createDirectory(root.resolve("data"));
        Files.writeString(folder.resolve("escrow.yml"), "escrow: [unterminated\n");
        assertThrows(IllegalStateException.class, () -> new Escrow(folder.toFile(), Logger.getLogger("test")).load(Set.of()));
    }
    @Test void persistenceFailurePropagatesInsteadOfReleasingAnUnwrittenEscrow() throws Exception {
        Path folder = Files.createDirectory(root.resolve("data"));
        var store = new Escrow(folder.toFile(), Logger.getLogger("test"));
        store.load(Set.of());
        Files.move(folder, root.resolve("saved")); Files.createFile(folder);
        assertThrows(java.io.UncheckedIOException.class, () -> store.release(UUID.randomUUID()));
    }
}
