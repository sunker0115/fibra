package com.sstlfsj.fibra.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;

final class EngineCrashRecovery {
    private final Path installedRoot;
    private final Path configBase;
    private final Path transactionsRoot;

    EngineCrashRecovery(Path installedRoot, Path configLocation) {
        this.installedRoot = installedRoot.toAbsolutePath().normalize();
        this.configBase = configLocation.toAbsolutePath().normalize().getParent();
        this.transactionsRoot = this.installedRoot.resolve(".fibra-engine/transactions");
    }

    void recover() {
        if (!Files.exists(transactionsRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var transactions = Files.list(transactionsRoot)) {
            for (var transaction : transactions.sorted().toList()) {
                recover(transaction);
            }
            deleteIfEmpty(transactionsRoot);
        } catch (FibraDeploymentException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw recoveryFailure(transactionsRoot, "cannot recover engine transaction",
                failure);
        }
    }

    private void recover(Path transaction) throws IOException {
        if (!Files.isDirectory(transaction, LinkOption.NOFOLLOW_LINKS)) {
            throw recoveryFailure(transaction,
                "engine transaction entry is not a directory", null);
        }
        var journalPath = transaction.resolve(EngineTransactionJournal.FILE_NAME);
        if (!Files.isRegularFile(journalPath, LinkOption.NOFOLLOW_LINKS)) {
            if (containsPreviousPayload(transaction)) {
                throw recoveryFailure(transaction,
                    "engine transaction has previous payload without a journal", null);
            }
            deleteTree(transaction);
            return;
        }
        final EngineTransactionJournal journal;
        try {
            journal = EngineTransactionJournal.read(transaction);
        } catch (RuntimeException failure) {
            throw recoveryFailure(transaction, "engine transaction journal is invalid",
                failure);
        }
        if (!transaction.getFileName().toString().equals(journal.transactionId())) {
            throw recoveryFailure(transaction,
                "engine transaction id does not match its directory", null);
        }
        switch (journal.state()) {
            case PREPARING -> { }
            case PREPARED -> validateOldState(transaction, journal);
            case COMMITTING_ARTIFACTS, COMMITTING_CONFIG, VERIFYING, ROLLING_BACK ->
                restoreOldState(transaction, journal);
            case COMMITTED -> {
                validateNewState(journal);
                AppliedRevisionStore.write(installedRoot, journal);
            }
        }
        deleteTree(transaction);
    }

    private void validateOldState(Path transaction,
                                  EngineTransactionJournal journal) throws IOException {
        for (var artifact : journal.artifacts()) {
            var installed = installedRoot.resolve(artifact.id());
            if (artifact.oldExists()) {
                requireDigest(installed, artifact.oldDigest(), transaction,
                    "installed old artifact " + artifact.id());
            } else if (Files.exists(installed, LinkOption.NOFOLLOW_LINKS)) {
                throw recoveryFailure(transaction,
                    "new artifact exists before commit: " + artifact.id(), null);
            }
        }
        for (var config : journal.configFiles()) {
            var target = target(config, transaction);
            if (config.oldExists()) {
                requireDigest(target, config.oldDigest(), transaction,
                    "old config " + config.relativePath());
            } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw recoveryFailure(transaction,
                    "new config exists before commit: " + config.relativePath(), null);
            }
        }
    }

    private void restoreOldState(Path transaction,
                                 EngineTransactionJournal journal) throws IOException {
        for (int index = journal.configFiles().size() - 1; index >= 0; index--) {
            restoreConfig(transaction, journal.configFiles().get(index));
        }
        for (int index = journal.artifacts().size() - 1; index >= 0; index--) {
            restoreArtifact(transaction, journal.artifacts().get(index));
        }
    }

    private void restoreConfig(Path transaction,
                               EngineTransactionJournal.ConfigFile config) throws IOException {
        var target = target(config, transaction);
        var previous = transaction.resolve("config/previous").resolve(config.relativePath());
        var targetDigest = digestIfPresent(target, transaction);
        var previousDigest = digestIfPresent(previous, transaction);
        if (config.oldExists()) {
            if (config.oldDigest().equals(targetDigest)) {
                return;
            }
            if (!config.oldDigest().equals(previousDigest)) {
                throw recoveryFailure(transaction,
                    "old config cannot be proven: " + config.relativePath(), null);
            }
            copyDurably(previous, target);
            requireDigest(target, config.oldDigest(), transaction,
                "restored config " + config.relativePath());
            return;
        }
        if (targetDigest == null) {
            return;
        }
        if (!config.newDigest().equals(targetDigest)) {
            throw recoveryFailure(transaction,
                "new config has unknown digest: " + config.relativePath(), null);
        }
        Files.delete(target);
        EngineTransactionJournal.forceDirectory(target.getParent());
    }

    private void restoreArtifact(Path transaction,
                                 EngineTransactionJournal.Artifact artifact) throws IOException {
        var installed = installedRoot.resolve(artifact.id());
        var previous = transaction.resolve("artifacts/previous").resolve(artifact.id());
        var next = transaction.resolve("artifacts/next").resolve(artifact.id());
        var installedDigest = digestIfPresent(installed, transaction);
        var previousDigest = digestIfPresent(previous, transaction);
        var nextDigest = digestIfPresent(next, transaction);
        if (artifact.oldExists()) {
            if (artifact.oldDigest().equals(installedDigest)) {
                if (previousDigest != null) {
                    throw recoveryFailure(transaction,
                        "old artifact exists in two locations: " + artifact.id(), null);
                }
                return;
            }
            if (!artifact.oldDigest().equals(previousDigest)) {
                throw recoveryFailure(transaction,
                    "old artifact cannot be proven: " + artifact.id(), null);
            }
            if (installedDigest != null) {
                if (!artifact.newDigest().equals(installedDigest) || nextDigest != null) {
                    throw recoveryFailure(transaction,
                        "new artifact location is ambiguous: " + artifact.id(), null);
                }
                moveDurably(installed, next);
            } else if (nextDigest != null && !artifact.newDigest().equals(nextDigest)) {
                throw recoveryFailure(transaction,
                    "prepared artifact has unknown digest: " + artifact.id(), null);
            }
            moveDurably(previous, installed);
            requireDigest(installed, artifact.oldDigest(), transaction,
                "restored artifact " + artifact.id());
            return;
        }
        if (installedDigest == null) {
            if (nextDigest != null && !artifact.newDigest().equals(nextDigest)) {
                throw recoveryFailure(transaction,
                    "prepared new artifact has unknown digest: " + artifact.id(), null);
            }
            return;
        }
        if (!artifact.newDigest().equals(installedDigest) || nextDigest != null
            || previousDigest != null) {
            throw recoveryFailure(transaction,
                "new artifact location is ambiguous: " + artifact.id(), null);
        }
        moveDurably(installed, next);
    }

    private void validateNewState(EngineTransactionJournal journal) throws IOException {
        for (var artifact : journal.artifacts()) {
            requireDigest(installedRoot.resolve(artifact.id()), artifact.newDigest(),
                transactionsRoot, "committed artifact " + artifact.id());
        }
        for (var config : journal.configFiles()) {
            requireDigest(target(config, transactionsRoot), config.newDigest(),
                transactionsRoot, "committed config " + config.relativePath());
        }
    }

    private Path target(EngineTransactionJournal.ConfigFile config, Path transaction) {
        var target = configBase.resolve(config.relativePath()).normalize();
        if (!target.startsWith(configBase)) {
            throw recoveryFailure(transaction, "config recovery path leaves config root", null);
        }
        return target;
    }

    private static String digestIfPresent(Path path, Path transaction) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            return EngineTransactionJournal.digest(path);
        } catch (IOException | RuntimeException failure) {
            throw recoveryFailure(transaction, "cannot digest recovery path " + path, failure);
        }
    }

    private static void requireDigest(Path path, String expected, Path transaction,
                                      String description) throws IOException {
        var actual = digestIfPresent(path, transaction);
        if (!expected.equals(actual)) {
            throw recoveryFailure(transaction,
                description + " digest does not match journal", null);
        }
    }

    private static void copyDurably(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        var temporary = Files.createTempFile(target.getParent(), ".fibra-recovery-", ".tmp");
        try {
            try (var input = Files.newInputStream(source);
                 var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0; ) {
                    var bytes = ByteBuffer.wrap(buffer, 0, read);
                    while (bytes.hasRemaining()) {
                        output.write(bytes);
                    }
                }
                output.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
            EngineTransactionJournal.forceDirectory(target.getParent());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveDurably(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        var sourceParent = source.getParent();
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        EngineTransactionJournal.forceDirectory(sourceParent);
        if (!sourceParent.equals(target.getParent())) {
            EngineTransactionJournal.forceDirectory(target.getParent());
        }
    }

    private static boolean containsPreviousPayload(Path transaction) throws IOException {
        try (var paths = Files.walk(transaction)) {
            return paths.anyMatch(path -> path.getNameCount() > transaction.getNameCount()
                && path.getParent() != null
                && path.getParent().getFileName().toString().equals("previous"));
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
        if (root.getParent() != null && Files.isDirectory(root.getParent())) {
            EngineTransactionJournal.forceDirectory(root.getParent());
        }
    }

    private static void deleteIfEmpty(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.list(directory)) {
            if (paths.findAny().isPresent()) {
                return;
            }
        }
        Files.delete(directory);
        EngineTransactionJournal.forceDirectory(directory.getParent());
    }

    private static FibraDeploymentException recoveryFailure(Path path, String message,
                                                            Throwable cause) {
        return new FibraDeploymentException(FibraDeploymentErrorStage.ROLLBACK, path,
            message, cause);
    }
}
