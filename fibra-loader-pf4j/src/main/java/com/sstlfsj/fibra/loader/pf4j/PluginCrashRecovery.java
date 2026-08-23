package com.sstlfsj.fibra.loader.pf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

final class PluginCrashRecovery {
    static final String PREFLIGHT_DIRECTORY = ".fibra-preflight";
    static final String TRANSACTIONS_DIRECTORY = ".fibra-transactions";

    private final Path pluginsRoot;
    private final PluginPackageInspector inspector = new PluginPackageInspector();

    PluginCrashRecovery(Path pluginsRoot) {
        this.pluginsRoot = pluginsRoot.toAbsolutePath().normalize();
    }

    void recover() {
        try {
            deleteTree(pluginsRoot.resolve(PREFLIGHT_DIRECTORY));
            var transactions = pluginsRoot.resolve(TRANSACTIONS_DIRECTORY);
            if (!Files.exists(transactions, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            try (var paths = Files.list(transactions)) {
                for (var transaction : paths.sorted().toList()) {
                    recoverTransaction(transaction);
                }
            }
            deleteIfEmpty(transactions);
        } catch (FibraArtifactException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw rollback(List.of(), "cannot recover plugin transaction", exception);
        }
    }

    void restoreUncommitted(Path transactionRoot,
                            PluginTransactionJournal journal) throws IOException {
        if (journal.state() == PluginTransactionState.COMMITTED) {
            throw new IllegalArgumentException("committed transaction cannot be rolled back");
        }
        if (journal.state() == PluginTransactionState.PREPARED) {
            validatePrepared(transactionRoot, journal);
        } else {
            restoreOldGraph(transactionRoot, journal);
        }
    }

    void cleanupTransaction(Path transactionRoot) throws IOException {
        cleanup(transactionRoot);
        deleteIfEmpty(pluginsRoot.resolve(TRANSACTIONS_DIRECTORY));
    }

    private void recoverTransaction(Path transactionRoot) throws IOException {
        if (!Files.isDirectory(transactionRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw rollback(List.of(), "plugin transaction entry is not a directory", null);
        }
        var journalPath = transactionRoot.resolve(PluginTransactionJournal.FILE_NAME);
        if (!Files.isRegularFile(journalPath, LinkOption.NOFOLLOW_LINKS)) {
            if (isEmpty(transactionRoot)) {
                Files.delete(transactionRoot);
                PluginTransactionJournal.forceDirectory(transactionRoot.getParent());
                return;
            }
            throw rollback(List.of(),
                "plugin transaction payload exists without a journal", null);
        }

        final PluginTransactionJournal journal;
        try {
            journal = PluginTransactionJournal.read(transactionRoot);
        } catch (RuntimeException exception) {
            throw rollback(List.of(), "plugin transaction journal is invalid", exception);
        }
        if (!transactionRoot.getFileName().toString().equals(journal.transactionId())) {
            throw rollback(journal.artifacts().stream().map(
                PluginTransactionJournal.Artifact::id).toList(),
                "plugin transaction id does not match its directory", null);
        }

        if (journal.cleanupOutcome()
            == PluginTransactionJournal.CleanupOutcome.ROLLBACK) {
            validateRolledBack(journal);
        } else {
            switch (journal.state()) {
                case PREPARED -> validatePrepared(transactionRoot, journal);
                case INSTALLING, APPLYING -> restoreOldGraph(transactionRoot, journal);
                case COMMITTED -> validateCommitted(journal);
            }
        }
        cleanup(transactionRoot);
    }

    private void validatePrepared(Path transactionRoot, PluginTransactionJournal journal) {
        for (var artifact : journal.artifacts()) {
            var installed = pluginsRoot.resolve(artifact.id());
            if (artifact.oldExists()) {
                requireDigest(installed, artifact.oldDigest(), artifact.id(), "installed old");
            } else if (Files.exists(installed, LinkOption.NOFOLLOW_LINKS)) {
                throw impossible(artifact.id(), "new plugin was installed in PREPARED state");
            }
            var next = transactionRoot.resolve("next").resolve(artifact.id());
            if (Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                requireDigest(next, artifact.newDigest(), artifact.id(), "prepared new");
            }
            if (Files.exists(transactionRoot.resolve("previous").resolve(artifact.id()),
                LinkOption.NOFOLLOW_LINKS)) {
                throw impossible(artifact.id(), "old plugin was moved in PREPARED state");
            }
        }
    }

    private void restoreOldGraph(Path transactionRoot, PluginTransactionJournal journal)
        throws IOException {
        for (int index = journal.artifacts().size() - 1; index >= 0; index--) {
            restoreArtifact(transactionRoot, journal.artifacts().get(index));
        }
        for (var artifact : journal.artifacts()) {
            var installed = pluginsRoot.resolve(artifact.id());
            if (artifact.oldExists()) {
                requireDigest(installed, artifact.oldDigest(), artifact.id(), "restored old");
            } else if (Files.exists(installed, LinkOption.NOFOLLOW_LINKS)) {
                throw impossible(artifact.id(), "new plugin remains after recovery");
            }
        }
    }

    private void restoreArtifact(Path transactionRoot,
                                 PluginTransactionJournal.Artifact artifact)
        throws IOException {
        var installed = pluginsRoot.resolve(artifact.id());
        var next = transactionRoot.resolve("next").resolve(artifact.id());
        var previous = transactionRoot.resolve("previous").resolve(artifact.id());
        var installedExists = Files.exists(installed, LinkOption.NOFOLLOW_LINKS);
        var nextExists = Files.exists(next, LinkOption.NOFOLLOW_LINKS);
        var previousExists = Files.exists(previous, LinkOption.NOFOLLOW_LINKS);

        if (artifact.oldExists() && previousExists) {
            requireDigest(previous, artifact.oldDigest(), artifact.id(), "previous old");
            requireExactlyOneNewLocation(artifact, installed, installedExists, next, nextExists);
            if (installedExists) {
                PluginTransactionJournal.moveDurably(installed, next);
            }
            PluginTransactionJournal.moveDurably(previous, installed);
            return;
        }
        if (artifact.oldExists()) {
            requireDigest(installed, artifact.oldDigest(), artifact.id(), "installed old");
            requireDigest(next, artifact.newDigest(), artifact.id(), "pending new");
            return;
        }
        if (previousExists) {
            throw impossible(artifact.id(), "new plugin has a previous directory");
        }
        requireExactlyOneNewLocation(artifact, installed, installedExists, next, nextExists);
        if (installedExists) {
            PluginTransactionJournal.moveDurably(installed, next);
        }
    }

    private void requireExactlyOneNewLocation(PluginTransactionJournal.Artifact artifact,
                                              Path installed, boolean installedExists,
                                              Path next, boolean nextExists) {
        if (installedExists == nextExists) {
            throw impossible(artifact.id(),
                "new plugin directory must exist in exactly one location");
        }
        requireDigest(installedExists ? installed : next, artifact.newDigest(), artifact.id(),
            "new");
    }

    private void validateCommitted(PluginTransactionJournal journal) {
        for (var artifact : journal.artifacts()) {
            requireDigest(pluginsRoot.resolve(artifact.id()), artifact.newDigest(),
                artifact.id(), "committed new");
        }
    }

    private void validateRolledBack(PluginTransactionJournal journal) {
        for (var artifact : journal.artifacts()) {
            var installed = pluginsRoot.resolve(artifact.id());
            if (artifact.oldExists()) {
                requireDigest(installed, artifact.oldDigest(), artifact.id(),
                    "rolled back old");
            } else if (Files.exists(installed, LinkOption.NOFOLLOW_LINKS)) {
                throw impossible(artifact.id(), "new plugin remains after rollback");
            }
        }
    }

    private void cleanup(Path transactionRoot) throws IOException {
        deleteTree(transactionRoot.resolve("previous"));
        deleteTree(transactionRoot.resolve("next"));
        deleteTree(transactionRoot.resolve("input"));
        try (var paths = Files.list(transactionRoot)) {
            for (var path : paths.filter(path -> path.getFileName().toString()
                .startsWith(".journal-")).toList()) {
                Files.delete(path);
            }
        }
        PluginTransactionJournal.forceDirectory(transactionRoot);
        Files.delete(transactionRoot.resolve(PluginTransactionJournal.FILE_NAME));
        PluginTransactionJournal.forceDirectory(transactionRoot);
        if (!isEmpty(transactionRoot)) {
            throw new IOException("plugin transaction directory is not empty after cleanup");
        }
        Files.delete(transactionRoot);
        PluginTransactionJournal.forceDirectory(transactionRoot.getParent());
    }

    private void requireDigest(Path path, String expected, String artifactId, String location) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw impossible(artifactId, location + " plugin directory is missing");
        }
        final String actual;
        try {
            actual = inspector.inspectDirectory(path).digest();
        } catch (RuntimeException exception) {
            throw rollback(List.of(artifactId),
                "cannot inspect " + location + " plugin directory", exception);
        }
        if (!actual.equals(expected)) {
            throw impossible(artifactId, location + " plugin digest does not match journal");
        }
    }

    private FibraArtifactException impossible(String artifactId, String message) {
        return rollback(List.of(artifactId), message, null);
    }

    private FibraArtifactException rollback(List<String> artifactIds, String message,
                                            Throwable cause) {
        return new FibraArtifactException(FibraArtifactErrorStage.ROLLBACK,
            List.of(pluginsRoot), artifactIds, message, cause);
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
        if (root.getParent() != null && Files.isDirectory(root.getParent())) {
            PluginTransactionJournal.forceDirectory(root.getParent());
        }
    }

    private static boolean isEmpty(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.findAny().isEmpty();
        }
    }

    private static void deleteIfEmpty(Path directory) throws IOException {
        if (Files.isDirectory(directory) && isEmpty(directory)) {
            Files.delete(directory);
            PluginTransactionJournal.forceDirectory(directory.getParent());
        }
    }
}
