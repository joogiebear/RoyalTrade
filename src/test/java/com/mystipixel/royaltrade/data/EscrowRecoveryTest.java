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
        var store = new Escrow(folder.toFile(), Logger.getLogger("test"), Runnable::run);
        assertEquals(0, store.load(Set.of(held)));
        String saved = Files.readString(file);
        assertTrue(saved.contains(held.toString()));
        assertFalse(saved.contains(ordinary.toString()));
    }
    @Test void corruptedEscrowCannotSilentlyLoadAsEmpty() throws Exception {
        Path folder = Files.createDirectory(root.resolve("data"));
        Files.writeString(folder.resolve("escrow.yml"), "escrow: [unterminated\n");
        assertThrows(IllegalStateException.class, () -> new Escrow(folder.toFile(), Logger.getLogger("test"), Runnable::run).load(Set.of()));
    }
    @Test void persistenceFailurePropagatesInsteadOfReleasingAnUnwrittenEscrow() throws Exception {
        Path folder = Files.createDirectory(root.resolve("data"));
        var store = new Escrow(folder.toFile(), Logger.getLogger("test"), Runnable::run);
        store.load(Set.of());
        Files.move(folder, root.resolve("saved")); Files.createFile(folder);
        store.release(UUID.randomUUID());
        assertThrows(java.io.UncheckedIOException.class, store::flush);
    }
    @Test void closeWritesTheLastChangeEvenIfNobodyFlushed() throws Exception {
        Path folder = Files.createDirectory(root.resolve("data"));
        UUID session = UUID.randomUUID();
        Files.writeString(folder.resolve("escrow.yml"), "escrow:\n  " + session + ":\n    " + UUID.randomUUID() + ": []\n");
        var store = new Escrow(folder.toFile(), Logger.getLogger("test"), Runnable::run);
        store.load(Set.of(session));
        store.release(session);
        store.close();
        assertFalse(Files.readString(folder.resolve("escrow.yml")).contains(session.toString()));
    }
    @Test void aFailedLoadNeverWritesOverTheFileOnClose() throws Exception {
        Path folder = Files.createDirectory(root.resolve("data"));
        String corrupt = "escrow: [unterminated\n";
        Files.writeString(folder.resolve("escrow.yml"), corrupt);
        var store = new Escrow(folder.toFile(), Logger.getLogger("test"), Runnable::run);
        assertThrows(IllegalStateException.class, () -> store.load(Set.of()));
        store.close();
        assertEquals(corrupt, Files.readString(folder.resolve("escrow.yml")));
    }
    @Test void afterAWriteLandsTheCallbackGoesThroughTheMainThreadExecutor() throws Exception {
        Path folder = Files.createDirectory(root.resolve("data"));
        List<Runnable> mainThread = Collections.synchronizedList(new ArrayList<>());
        var store = new Escrow(folder.toFile(), Logger.getLogger("test"), mainThread::add);
        store.load(Set.of());
        long seq = store.hold(UUID.randomUUID(), Map.of());
        boolean[] ran = {false};
        store.whenDurable(seq, () -> ran[0] = true);
        store.flush();
        long deadline = System.currentTimeMillis() + 5_000;
        while (mainThread.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertFalse(ran[0]);                                 // not on the writer thread
        mainThread.forEach(Runnable::run);
        assertTrue(ran[0]);
        assertTrue(store.durableSeq() >= seq);
        store.close();
    }
    @Test void stagedChangesWaitForTheNextWrite() throws Exception {
        Path folder = Files.createDirectory(root.resolve("data"));
        var store = new Escrow(folder.toFile(), Logger.getLogger("test"), Runnable::run);
        store.load(Set.of());
        store.stage(UUID.randomUUID(), Map.of(UUID.randomUUID(), List.of()));
        assertFalse(store.isDurable());
        long seq = store.saveStaged();
        store.flush();
        assertTrue(store.isDurable());
        assertEquals(seq, store.saveStaged());               // nothing new, so no new write
        store.close();
    }
}
