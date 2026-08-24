package com.sstlfsj.fibra.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

final class AppliedRevisionStore {
    private static final String FILE_NAME = "applied.properties";

    private AppliedRevisionStore() { }

    static void write(Path installedRoot, EngineTransactionJournal journal)
        throws IOException {
        if (journal.state() != EngineTransactionState.COMMITTED) {
            throw new IllegalArgumentException("applied revision requires COMMITTED journal");
        }
        var root = installedRoot.resolve(".fibra-engine/revisions");
        Files.createDirectories(root);
        var temporary = Files.createTempFile(root, ".applied-", ".tmp");
        try {
            var content = "deployment.id=" + journal.deploymentId() + '\n'
                + "deployment.version=" + journal.deploymentVersion() + '\n'
                + "package.digest=" + journal.packageDigest() + '\n'
                + "applied.revision=" + journal.appliedRevision() + '\n';
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            Files.move(temporary, root.resolve(FILE_NAME), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
            EngineTransactionJournal.forceDirectory(root);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

}
