package com.sstlfsj.fibra.loader.pf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

final class PreparedArtifactChange implements FibraArtifactChange {
    private final FibraPluginLoader loader;
    private final Path pluginsRoot;
    private final PluginPackageInspector inspector;
    private final PluginCrashRecovery recovery;
    private final List<Path> candidatePaths;
    private final List<String> changedArtifactIds;
    private final List<String> affectedArtifactIds;
    private final List<PluginTransactionJournal.Artifact> journalArtifacts;
    private final FibraPluginLoader.RuntimeSnapshot runtime;
    private final boolean noOp;
    private final List<Path> cleanupRoots = new ArrayList<>();

    private PreparedPluginCatalog targetCatalog;
    private Path workspace;
    private State state = State.PREPARED;

    private PreparedArtifactChange(FibraPluginLoader loader, Path pluginsRoot,
                                   PluginPackageInspector inspector,
                                   List<Path> candidatePaths,
                                   List<String> changedArtifactIds,
                                   List<String> affectedArtifactIds,
                                   List<PluginTransactionJournal.Artifact> journalArtifacts,
                                   FibraPluginLoader.RuntimeSnapshot runtime,
                                   PreparedPluginCatalog targetCatalog,
                                   Path workspace, boolean noOp) {
        this.loader = loader;
        this.pluginsRoot = pluginsRoot;
        this.inspector = inspector;
        this.recovery = new PluginCrashRecovery(pluginsRoot);
        this.candidatePaths = candidatePaths;
        this.changedArtifactIds = changedArtifactIds;
        this.affectedArtifactIds = affectedArtifactIds;
        this.journalArtifacts = journalArtifacts;
        this.runtime = runtime;
        this.targetCatalog = targetCatalog;
        this.workspace = workspace;
        this.cleanupRoots.add(workspace);
        this.noOp = noOp;
    }

    static PreparedArtifactChange prepare(FibraPluginLoader loader, Path pluginsRoot,
                                          PluginPackageInspector inspector,
                                          PluginGraphPreflight preflight,
                                          List<Path> candidatePaths, Path workspace) {
        var candidates = normalizeCandidates(candidatePaths);
        var normalizedWorkspace = validateWorkspace(workspace);
        PreparedPluginCatalog catalog = null;
        try {
            var inspectedCandidates = new ArrayList<InspectedPluginPackage>(candidates.size());
            for (var candidate : candidates) {
                inspectedCandidates.add(inspector.inspectCandidate(candidate,
                    normalizedWorkspace));
            }
            var current = loader.installedPackages();
            var result = preflight.validate(current, inspectedCandidates);
            validateSameVersionContents(current, inspectedCandidates);
            var prospective = prospectivePackages(current, inspectedCandidates);
            catalog = PreparedPluginCatalog.open(prospective);
            var noOp = isNoOp(current, inspectedCandidates);
            var runtime = loader.snapshotRuntime(result.affectedArtifactIds());
            return new PreparedArtifactChange(loader, pluginsRoot, inspector, candidates,
                result.candidateArtifactIds().stream().sorted().toList(),
                result.affectedArtifactIds(), journalArtifacts(current, inspectedCandidates),
                runtime, catalog, normalizedWorkspace, noOp);
        } catch (RuntimeException failure) {
            if (catalog != null) {
                try {
                    catalog.close();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            try {
                cleanupWorkspace(normalizedWorkspace);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw asArtifactFailure(failure, FibraArtifactErrorStage.INSTALL, candidates);
        }
    }

    @Override
    public List<String> changedArtifactIds() {
        requireUsable();
        return changedArtifactIds;
    }

    @Override
    public FibraPluginCatalog targetCatalog() {
        return targetCatalog;
    }

    @Override
    public void commit() {
        commit(CommitObserver.NONE);
    }

    void commit(CommitObserver observer) {
        loader.requireOperationOwner();
        Objects.requireNonNull(observer, "observer");
        requireState(State.PREPARED, "commit");
        state = State.COMMITTING;
        if (noOp) {
            state = State.COMMITTED;
            return;
        }
        try {
            loader.disposeAffected(affectedArtifactIds);
            observer.installing();
            installCandidates();
            observer.applying();
            loader.loadInstalledAffected(affectedArtifactIds);
            loader.restoreRuntime(runtime);
            state = State.COMMITTED;
        } catch (RuntimeException | IOException failure) {
            throw asArtifactFailure(failure, FibraArtifactErrorStage.APPLY, candidatePaths);
        }
    }

    @Override
    public void complete() {
        loader.requireOperationOwner();
        requireState(State.COMMITTED, "complete");
        closeCatalog();
        cleanupAll();
        state = State.COMPLETED;
    }

    @Override
    public void rollback() {
        rollback(() -> { });
    }

    void rollback(Runnable beforeCleanup) {
        loader.requireOperationOwner();
        Objects.requireNonNull(beforeCleanup, "beforeCleanup");
        if (state == State.ROLLED_BACK || state == State.COMPLETED) {
            return;
        }
        var failures = new ArrayList<Throwable>();
        if (state == State.COMMITTING || state == State.COMMITTED) {
            try {
                loader.disposeAffected(affectedArtifactIds);
            } catch (RuntimeException failure) {
                failures.add(failure);
            }
            if (!noOp) {
                try {
                    var journal = PluginTransactionJournal.prepared("participant",
                        journalArtifacts).advance(PluginTransactionState.INSTALLING);
                    recovery.restoreUncommitted(workspace, journal);
                } catch (RuntimeException | IOException failure) {
                    failures.add(failure);
                }
            }
            if (failures.isEmpty()) {
                try {
                    loader.loadInstalledAffected(affectedArtifactIds);
                    loader.restoreRuntime(runtime);
                } catch (RuntimeException failure) {
                    failures.add(failure);
                }
            }
        }
        try {
            closeCatalog();
        } catch (RuntimeException failure) {
            failures.add(failure);
        }
        if (failures.isEmpty()) {
            try {
                beforeCleanup.run();
                cleanupAll();
                state = State.ROLLED_BACK;
                return;
            } catch (RuntimeException failure) {
                failures.add(failure);
            }
        }
        var rollback = new FibraArtifactException(FibraArtifactErrorStage.ROLLBACK,
            candidatePaths, changedArtifactIds,
            "cannot roll back prepared plugin artifact change", null);
        failures.forEach(rollback::addSuppressed);
        throw rollback;
    }

    @Override
    public void close() {
        if (state != State.COMPLETED && state != State.ROLLED_BACK) {
            rollback();
        }
    }

    boolean isNoOp() {
        return noOp;
    }

    PluginTransactionJournal journal(String transactionId) {
        if (journalArtifacts.isEmpty()) {
            throw new IllegalStateException("no-op artifact change has no journal payload");
        }
        return PluginTransactionJournal.prepared(transactionId, journalArtifacts);
    }

    void relocate(Path targetWorkspace) {
        loader.requireOperationOwner();
        requireState(State.PREPARED, "relocate");
        var target = targetWorkspace.toAbsolutePath().normalize();
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("targetWorkspace must be an existing directory: "
                + target);
        }
        closeCatalog();
        cleanupRoots.add(target);
        try {
            moveIfPresent(workspace.resolve("input"), target.resolve("input"));
            moveIfPresent(workspace.resolve("next"), target.resolve("next"));
            Files.delete(workspace);
            PluginTransactionJournal.forceDirectory(workspace.getParent());
            cleanupRoots.remove(workspace);
            workspace = target;
        } catch (IOException failure) {
            workspace = target;
            throw new UncheckedIOException("cannot move prepared artifact payload", failure);
        }
    }

    private void installCandidates() throws IOException {
        var previousRoot = workspace.resolve("previous");
        for (var artifact : journalArtifacts) {
            var installed = pluginsRoot.resolve(artifact.id());
            if (artifact.oldExists()) {
                PluginTransactionJournal.moveDurably(installed,
                    previousRoot.resolve(artifact.id()));
            }
            PluginTransactionJournal.moveDurably(
                workspace.resolve("next").resolve(artifact.id()), installed);
        }
    }

    private void closeCatalog() {
        targetCatalog.close();
    }

    private void cleanupAll() {
        RuntimeException failure = null;
        for (int index = cleanupRoots.size() - 1; index >= 0; index--) {
            try {
                cleanupWorkspace(cleanupRoots.get(index));
            } catch (RuntimeException cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void requireUsable() {
        if (state == State.COMPLETED || state == State.ROLLED_BACK) {
            throw new IllegalStateException("artifact change is closed");
        }
    }

    private void requireState(State expected, String operation) {
        if (state != expected) {
            throw new IllegalStateException(operation + " requires " + expected
                + " artifact change state, actual=" + state);
        }
    }

    private static Path validateWorkspace(Path path) {
        Objects.requireNonNull(path, "workspace");
        var normalized = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("workspace must be an existing directory: "
                + normalized);
        }
        try (var children = Files.list(normalized)) {
            if (children.findAny().isPresent()) {
                throw new IllegalArgumentException("workspace must be empty: " + normalized);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot inspect workspace " + normalized,
                exception);
        }
        return normalized;
    }

    static List<Path> normalizeCandidates(List<Path> candidatePaths) {
        Objects.requireNonNull(candidatePaths, "candidatePaths");
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

    private static List<InspectedPluginPackage> prospectivePackages(
        List<InspectedPluginPackage> current, List<InspectedPluginPackage> candidates) {
        var packages = new LinkedHashMap<String, InspectedPluginPackage>();
        current.forEach(plugin -> packages.put(plugin.descriptor().getPluginId(), plugin));
        candidates.forEach(plugin -> packages.put(plugin.descriptor().getPluginId(), plugin));
        return packages.values().stream().sorted(java.util.Comparator.comparing(
            plugin -> plugin.descriptor().getPluginId())).toList();
    }

    private static List<PluginTransactionJournal.Artifact> journalArtifacts(
        List<InspectedPluginPackage> current, List<InspectedPluginPackage> candidates) {
        var currentById = new LinkedHashMap<String, InspectedPluginPackage>();
        current.forEach(plugin -> currentById.put(plugin.descriptor().getPluginId(), plugin));
        return candidates.stream().sorted(java.util.Comparator.comparing(
                plugin -> plugin.descriptor().getPluginId()))
            .map(candidate -> {
                var id = candidate.descriptor().getPluginId();
                var previous = currentById.get(id);
                return new PluginTransactionJournal.Artifact(id, previous != null,
                    previous == null ? null : previous.digest(), candidate.digest());
            }).toList();
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
        if (candidates.isEmpty()) {
            return true;
        }
        var digests = new LinkedHashMap<String, String>();
        current.forEach(plugin -> digests.put(plugin.descriptor().getPluginId(),
            plugin.digest()));
        return candidates.stream().allMatch(candidate -> Objects.equals(
            digests.get(candidate.descriptor().getPluginId()), candidate.digest()));
    }

    private static FibraArtifactException asArtifactFailure(
        Throwable failure, FibraArtifactErrorStage stage, List<Path> packages) {
        if (failure instanceof FibraArtifactException artifactFailure) {
            return artifactFailure;
        }
        return new FibraArtifactException(stage, packages, List.of(),
            "plugin artifact change failed during " + stage, failure);
    }

    private static void moveIfPresent(Path source, Path target) throws IOException {
        if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            PluginTransactionJournal.moveDurably(source, target);
        }
    }

    private static void cleanupWorkspace(Path workspace) {
        try {
            PluginCrashRecovery.deleteTree(workspace);
            var parent = workspace.getParent();
            if (parent != null && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                boolean empty;
                try (var children = Files.list(parent)) {
                    empty = children.findAny().isEmpty();
                }
                if (empty && (parent.getFileName().toString().equals(
                    PluginCrashRecovery.PREFLIGHT_DIRECTORY)
                    || parent.getFileName().toString().equals(
                    PluginCrashRecovery.TRANSACTIONS_DIRECTORY))) {
                    Files.delete(parent);
                    PluginTransactionJournal.forceDirectory(parent.getParent());
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot clean artifact workspace " + workspace,
                exception);
        }
    }

    interface CommitObserver {
        CommitObserver NONE = new CommitObserver() { };

        default void installing() { }

        default void applying() { }
    }

    private enum State {
        PREPARED,
        COMMITTING,
        COMMITTED,
        COMPLETED,
        ROLLED_BACK
    }
}
