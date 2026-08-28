package com.sstlfsj.fibra.loader.pf4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class PluginUpdateTransaction {
    private static final Logger LOGGER = LoggerFactory.getLogger(PluginUpdateTransaction.class);

    private final FibraPluginLoader loader;
    private final Path pluginsRoot;
    private final PluginPackageInspector inspector;
    private final PluginGraphPreflight preflight;

    PluginUpdateTransaction(FibraPluginLoader loader, Path pluginsRoot,
                            PluginPackageInspector inspector,
                            PluginGraphPreflight preflight) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.pluginsRoot = Objects.requireNonNull(pluginsRoot, "pluginsRoot");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.preflight = Objects.requireNonNull(preflight, "preflight");
    }

    List<String> apply(List<Path> candidatePaths) {
        var candidates = PreparedArtifactChange.normalizeCandidates(candidatePaths);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("candidatePaths must not be empty");
        }
        var transactionId = UUID.randomUUID().toString();
        var preflightRoot = pluginsRoot.resolve(PluginCrashRecovery.PREFLIGHT_DIRECTORY)
            .resolve(transactionId);
        var transactionRoot = pluginsRoot.resolve(PluginCrashRecovery.TRANSACTIONS_DIRECTORY)
            .resolve(transactionId);
        PreparedArtifactChange change = null;
        var journal = new PluginTransactionJournal[1];
        var formalStarted = false;
        try {
            Files.createDirectories(preflightRoot);
            change = PreparedArtifactChange.prepare(loader, pluginsRoot, inspector, preflight,
                candidates, preflightRoot);
            var changedIds = change.changedArtifactIds();
            if (change.isNoOp()) {
                change.commit();
                change.complete();
                return changedIds;
            }

            Files.createDirectories(transactionRoot);
            journal[0] = change.journal(transactionId);
            journal[0].write(transactionRoot);
            formalStarted = true;
            change.relocate(transactionRoot);
            change.commit(new PreparedArtifactChange.CommitObserver() {
                @Override
                public void installing() {
                    advanceAndWrite(journal, transactionRoot,
                        PluginTransactionState.INSTALLING);
                }

                @Override
                public void applying() {
                    advanceAndWrite(journal, transactionRoot,
                        PluginTransactionState.APPLYING);
                }
            });
            advanceAndWrite(journal, transactionRoot, PluginTransactionState.COMMITTED);
            try {
                change.complete();
            } catch (RuntimeException cleanupFailure) {
                LOGGER.atWarn()
                    .setCause(cleanupFailure)
                    .log("event=fibra.loader.plugin.cleanup_deferred transactionId={} pluginIds={}",
                        transactionId, changedIds);
            }
            return changedIds;
        } catch (RuntimeException | IOException failure) {
            var original = asArtifactFailure(failure,
                formalStarted ? stage(journal[0]) : FibraArtifactErrorStage.INSTALL,
                candidates);
            if (change == null) {
                deletePreflight(preflightRoot, original);
                throw original;
            }
            try {
                if (formalStarted) {
                    change.rollback(() -> {
                        journal[0] = journal[0].markRollbackCleanup();
                        write(journal[0], transactionRoot);
                    });
                } else {
                    change.rollback();
                }
            } catch (FibraArtifactException rollbackFailure) {
                var rollback = new FibraArtifactException(FibraArtifactErrorStage.ROLLBACK,
                    original.packages(), original.artifactIds(),
                    "cannot roll back plugin artifact transaction", original);
                rollback.addSuppressed(rollbackFailure);
                throw rollback;
            }
            throw original;
        }
    }

    private static void advanceAndWrite(PluginTransactionJournal[] holder, Path root,
                                        PluginTransactionState state) {
        holder[0] = holder[0].advance(state);
        write(holder[0], root);
    }

    private static void write(PluginTransactionJournal journal, Path root) {
        try {
            journal.write(root);
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot write plugin transaction journal", exception);
        }
    }

    private static FibraArtifactErrorStage stage(PluginTransactionJournal journal) {
        if (journal == null || journal.state() == PluginTransactionState.PREPARED) {
            return FibraArtifactErrorStage.DISPOSE;
        }
        return journal.state() == PluginTransactionState.INSTALLING
            ? FibraArtifactErrorStage.INSTALL : FibraArtifactErrorStage.APPLY;
    }

    private static FibraArtifactException asArtifactFailure(
        Throwable failure, FibraArtifactErrorStage stage, List<Path> packages) {
        if (failure instanceof FibraArtifactException artifactFailure) {
            return artifactFailure;
        }
        return new FibraArtifactException(stage, packages, List.of(),
            "plugin artifact transaction failed during " + stage, failure);
    }

    private static void deletePreflight(Path preflightRoot, RuntimeException original) {
        try {
            PluginCrashRecovery.deleteTree(preflightRoot);
        } catch (IOException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }
}
