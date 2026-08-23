package com.sstlfsj.fibra.loader.pf4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class PluginUpdateTransaction {
    private static final Logger LOGGER = LoggerFactory.getLogger(PluginUpdateTransaction.class);

    private final FibraPluginLoader loader;
    private final Path pluginsRoot;
    private final PluginPackageInspector inspector;
    private final PluginGraphPreflight preflight;
    private final PluginCrashRecovery recovery;

    PluginUpdateTransaction(FibraPluginLoader loader, Path pluginsRoot,
                            PluginPackageInspector inspector,
                            PluginGraphPreflight preflight) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.pluginsRoot = Objects.requireNonNull(pluginsRoot, "pluginsRoot");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.preflight = Objects.requireNonNull(preflight, "preflight");
        this.recovery = new PluginCrashRecovery(pluginsRoot);
    }

    List<String> apply(List<Path> candidatePaths) {
        var candidates = normalizeCandidates(candidatePaths);
        var transactionId = UUID.randomUUID().toString();
        var preflightRoot = pluginsRoot.resolve(PluginCrashRecovery.PREFLIGHT_DIRECTORY)
            .resolve(transactionId);
        Path transactionRoot = null;
        PluginTransactionJournal journal = null;
        FibraPluginLoader.RuntimeSnapshot runtime = null;
        var formalStarted = false;
        try {
            var inspectedCandidates = new ArrayList<InspectedPluginPackage>(candidates.size());
            for (var candidate : candidates) {
                inspectedCandidates.add(inspector.inspectCandidate(candidate, preflightRoot));
            }
            var current = loader.installedPackages();
            var result = preflight.validate(current, inspectedCandidates);
            validateSameVersionContents(current, inspectedCandidates);
            if (isNoOp(current, inspectedCandidates)) {
                deletePreflight(preflightRoot);
                return result.candidateArtifactIds().stream().sorted().toList();
            }

            runtime = loader.snapshotRuntime(result.affectedArtifactIds());
            journal = createJournal(transactionId, current, inspectedCandidates);
            transactionRoot = pluginsRoot.resolve(
                PluginCrashRecovery.TRANSACTIONS_DIRECTORY).resolve(transactionId);
            Files.createDirectories(transactionRoot);
            journal.write(transactionRoot);
            formalStarted = true;
            movePreflightPayload(preflightRoot, transactionRoot);

            loader.disposeAffected(runtime.affectedArtifactIds());
            journal = journal.advance(PluginTransactionState.INSTALLING);
            journal.write(transactionRoot);
            installCandidates(transactionRoot, journal);

            journal = journal.advance(PluginTransactionState.APPLYING);
            journal.write(transactionRoot);
            loader.loadInstalledAffected(runtime.affectedArtifactIds());
            loader.restoreRuntime(runtime);

            journal = journal.advance(PluginTransactionState.COMMITTED);
            journal.write(transactionRoot);
            try {
                recovery.cleanupTransaction(transactionRoot);
            } catch (IOException cleanupFailure) {
                LOGGER.warn("Cannot finish committed Fibra plugin transaction cleanup: {}",
                    transactionRoot, cleanupFailure);
            }
            return journal.artifacts().stream()
                .map(PluginTransactionJournal.Artifact::id).toList();
        } catch (RuntimeException | IOException failure) {
            if (!formalStarted) {
                deletePreflight(preflightRoot);
                throw asArtifactFailure(failure, FibraArtifactErrorStage.INSTALL, candidates);
            }
            var original = asArtifactFailure(failure, stage(journal), candidates);
            throw rollBack(transactionRoot, journal, runtime, original);
        }
    }

    private RuntimeException rollBack(Path transactionRoot,
                                      PluginTransactionJournal journal,
                                      FibraPluginLoader.RuntimeSnapshot runtime,
                                      FibraArtifactException original) {
        var failures = new ArrayList<Throwable>();
        if (runtime != null) {
            try {
                loader.disposeAffected(runtime.affectedArtifactIds());
            } catch (RuntimeException failure) {
                failures.add(failure);
            }
        }
        try {
            recovery.restoreUncommitted(transactionRoot, journal);
        } catch (RuntimeException | IOException failure) {
            failures.add(failure);
        }
        if (runtime != null && failures.isEmpty()) {
            try {
                loader.loadInstalledAffected(runtime.affectedArtifactIds());
                loader.restoreRuntime(runtime);
            } catch (RuntimeException failure) {
                failures.add(failure);
            }
        }
        if (failures.isEmpty()) {
            try {
                journal.markRollbackCleanup().write(transactionRoot);
                recovery.cleanupTransaction(transactionRoot);
            } catch (RuntimeException | IOException failure) {
                failures.add(failure);
            }
        }
        if (failures.isEmpty()) {
            return original;
        }
        var rollback = new FibraArtifactException(FibraArtifactErrorStage.ROLLBACK,
            original.packages(), original.artifactIds(),
            "cannot roll back plugin artifact transaction", original);
        failures.forEach(rollback::addSuppressed);
        return rollback;
    }

    private void installCandidates(Path transactionRoot,
                                   PluginTransactionJournal journal) throws IOException {
        var previousRoot = transactionRoot.resolve("previous");
        for (var artifact : journal.artifacts()) {
            var installed = pluginsRoot.resolve(artifact.id());
            if (artifact.oldExists()) {
                PluginTransactionJournal.moveDurably(installed,
                    previousRoot.resolve(artifact.id()));
            }
            PluginTransactionJournal.moveDurably(
                transactionRoot.resolve("next").resolve(artifact.id()), installed);
        }
    }

    private static PluginTransactionJournal createJournal(
        String transactionId, List<InspectedPluginPackage> current,
        List<InspectedPluginPackage> candidates) {
        var currentById = new LinkedHashMap<String, InspectedPluginPackage>();
        current.forEach(plugin -> currentById.put(plugin.descriptor().getPluginId(), plugin));
        var artifacts = candidates.stream()
            .sorted(java.util.Comparator.comparing(
                plugin -> plugin.descriptor().getPluginId()))
            .map(candidate -> {
                var id = candidate.descriptor().getPluginId();
                var previous = currentById.get(id);
                return new PluginTransactionJournal.Artifact(id, previous != null,
                    previous == null ? null : previous.digest(), candidate.digest());
            })
            .toList();
        return PluginTransactionJournal.prepared(transactionId, artifacts);
    }

    private static void movePreflightPayload(Path preflightRoot, Path transactionRoot)
        throws IOException {
        PluginTransactionJournal.moveDurably(preflightRoot.resolve("input"),
            transactionRoot.resolve("input"));
        PluginTransactionJournal.moveDurably(preflightRoot.resolve("next"),
            transactionRoot.resolve("next"));
        Files.delete(preflightRoot);
        PluginTransactionJournal.forceDirectory(preflightRoot.getParent());
    }

    private static List<Path> normalizeCandidates(List<Path> candidatePaths) {
        Objects.requireNonNull(candidatePaths, "candidatePaths");
        if (candidatePaths.isEmpty()) {
            throw new IllegalArgumentException("candidatePaths must not be empty");
        }
        var unique = new LinkedHashSet<Path>();
        for (var candidate : candidatePaths) {
            var normalized = Objects.requireNonNull(candidate, "candidatePath")
                .toAbsolutePath().normalize();
            if (!unique.add(normalized)) {
                throw new IllegalArgumentException("duplicate candidate path " + normalized);
            }
        }
        return List.copyOf(unique);
    }

    private static void validateSameVersionContents(
        List<InspectedPluginPackage> current, List<InspectedPluginPackage> candidates) {
        var currentById = new LinkedHashMap<String, InspectedPluginPackage>();
        current.forEach(plugin -> currentById.put(plugin.descriptor().getPluginId(), plugin));
        for (var candidate : candidates) {
            var installed = currentById.get(candidate.descriptor().getPluginId());
            if (installed != null
                && installed.descriptor().getVersion().equals(
                    candidate.descriptor().getVersion())
                && !installed.digest().equals(candidate.digest())) {
                throw new FibraArtifactException(FibraArtifactErrorStage.VALIDATE,
                    List.of(candidate.packageRoot()),
                    List.of(candidate.descriptor().getPluginId()),
                    "same plugin version has different package content", null);
            }
        }
    }

    private static boolean isNoOp(List<InspectedPluginPackage> current,
                                  List<InspectedPluginPackage> candidates) {
        var digests = new LinkedHashMap<String, String>();
        current.forEach(plugin -> digests.put(plugin.descriptor().getPluginId(),
            plugin.digest()));
        return candidates.stream().allMatch(candidate -> Objects.equals(
            digests.get(candidate.descriptor().getPluginId()), candidate.digest()));
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

    private static void deletePreflight(Path preflightRoot) {
        try {
            PluginCrashRecovery.deleteTree(preflightRoot);
        } catch (IOException exception) {
            throw new FibraArtifactException(FibraArtifactErrorStage.ROLLBACK,
                List.of(preflightRoot), List.of(), "cannot clean plugin preflight", exception);
        }
    }
}
