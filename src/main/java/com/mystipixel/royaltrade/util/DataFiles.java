package com.mystipixel.royaltrade.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class DataFiles {

    private DataFiles() {
    }

    /** Writes a synced sibling and moves it over {@code target}, so a crash never leaves half a file. */
    public static void write(Path target, String data) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
