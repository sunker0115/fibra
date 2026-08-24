package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.FibraState;
import com.sstlfsj.fibra.loader.config.FibraConfigLoader;
import com.sstlfsj.fibra.loader.pf4j.FibraArtifactDescriptor;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.pf4j.DefaultVersionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 框架中立的插件、配置、收敛和部署事务唯一托管入口。 */
public final class FibraEngine implements AutoCloseable {
    private final Object stateMonitor = new Object();
    private final Context root;
    private final FibraPluginLoader artifacts;
    private final FibraConfigLoader config;
    private final Path installedRoot;
    private final Path configLocation;
    private final Path artifactSourceRoot;
    private final Duration artifactDebounce;
    private final boolean configSourceEnabled;
    private final Duration configDebounce;
    private final List<String> requiredEntries;
    private final Duration readinessTimeout;
    private final Duration rootCloseTimeout;
    private final Duration resyncInterval;
    private final Duration retryInitial;
    private final Duration retryMaximum;
    private final EnumMap<FibraEngineFailureStage, FibraEngineFailure> failures =
        new EnumMap<>(FibraEngineFailureStage.class);

    private FibraEngineState state = FibraEngineState.NEW;
    private String desiredRevision;
    private String appliedRevision;
    private ReconcileCoordinator reconcileCoordinator;
    private ArtifactDirectorySource artifactSource;
    private ConfigFileSource configSource;

    private FibraEngine(Builder builder) {
        installedRoot = builder.installedRoot;
        configLocation = builder.configLocation;
        artifactSourceRoot = builder.artifactSourceRoot;
        artifactDebounce = builder.artifactDebounce;
        configSourceEnabled = builder.configSourceEnabled;
        configDebounce = builder.configDebounce;
        requiredEntries = List.copyOf(builder.requiredEntries);
        readinessTimeout = builder.readinessTimeout;
        rootCloseTimeout = builder.rootCloseTimeout;
        resyncInterval = builder.resyncInterval;
        retryInitial = builder.retryInitial;
        retryMaximum = builder.retryMaximum;
        validateLayout();
        try {
            Files.createDirectories(installedRoot.resolve(".fibra-engine"));
            new EngineCrashRecovery(installedRoot, configLocation).recover();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot create Fibra engine state root", exception);
        }
        root = FibraRuntime.create();
        FibraPluginLoader createdArtifacts = null;
        FibraConfigLoader createdConfig = null;
        try {
            createdArtifacts = new FibraPluginLoader(root, installedRoot);
            createdConfig = FibraConfigLoader.builder(root, createdArtifacts,
                configLocation).build();
        } catch (RuntimeException failure) {
            if (createdConfig != null) {
                createdConfig.close();
            }
            if (createdArtifacts != null) {
                createdArtifacts.close();
            }
            root.close();
            throw failure;
        }
        artifacts = createdArtifacts;
        config = createdConfig;
    }

    public static Builder builder(Path installedRoot, Path configLocation) {
        return new Builder(installedRoot, configLocation);
    }

    public void start() {
        synchronized (stateMonitor) {
            if (state != FibraEngineState.NEW) {
                throw new IllegalStateException("FibraEngine can only be started once");
            }
            state = FibraEngineState.STARTING;
        }
        FibraEngineFailureStage stage = FibraEngineFailureStage.STARTUP;
        try {
            artifacts.loadArtifacts();
            config.load();
            var initialRevision = currentRevision();
            synchronized (stateMonitor) {
                desiredRevision = initialRevision;
                appliedRevision = initialRevision;
            }
            stage = FibraEngineFailureStage.READINESS;
            awaitRequiredEntries();
            stage = FibraEngineFailureStage.STARTUP;
            reconcileCoordinator = new ReconcileCoordinator(this::reconcile, resyncInterval,
                retryInitial, retryMaximum,
                failure -> recordFailure(FibraEngineFailureStage.CONFIG_RECONCILE,
                    failure));
            if (artifactSourceRoot != null) {
                artifactSource = new ArtifactDirectorySource(artifactSourceRoot,
                    artifactDebounce, reconcileCoordinator::request);
            }
            if (configSourceEnabled) {
                configSource = new ConfigFileSource(config::sourcePaths, configDebounce,
                    reconcileCoordinator::request);
            }
            reconcileCoordinator.start();
            if (artifactSource != null) {
                artifactSource.start();
            }
            if (configSource != null) {
                configSource.start();
            }
            synchronized (stateMonitor) {
                state = FibraEngineState.RUNNING;
            }
        } catch (RuntimeException failure) {
            recordFailure(stage, failure);
            try {
                terminateAfterStartFailure();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public void requestReconcile() {
        ReconcileCoordinator current;
        synchronized (stateMonitor) {
            if (state != FibraEngineState.RUNNING
                && state != FibraEngineState.DEGRADED) {
                throw new IllegalStateException("FibraEngine is not running");
            }
            current = reconcileCoordinator;
        }
        current.request();
    }

    public FibraDeploymentResult applyDeployment(Path packagePath) {
        Objects.requireNonNull(packagePath, "packagePath");
        ReconcileCoordinator current;
        synchronized (stateMonitor) {
            if (state != FibraEngineState.RUNNING
                && state != FibraEngineState.DEGRADED) {
                throw new IllegalStateException("FibraEngine is not running");
            }
            current = reconcileCoordinator;
        }
        return current.execute(() -> applyDeploymentSerialized(packagePath));
    }

    private FibraDeploymentResult applyDeploymentSerialized(Path packagePath) {
        var transactionId = java.util.UUID.randomUUID().toString();
        var transactionRoot = installedRoot.resolve(".fibra-engine/transactions")
            .resolve(transactionId);
        try {
            Files.createDirectories(transactionRoot);
            EngineTransactionJournal.preparing(transactionId).write(transactionRoot);
            var inspected = new DeploymentPackageInspector().inspect(packagePath,
                transactionRoot.resolve("input"));
            var result = artifacts.runExclusive(() -> applyDeployment(inspected,
                transactionRoot));
            clearFailure(FibraEngineFailureStage.DEPLOYMENT);
            return result;
        } catch (FibraDeploymentException failure) {
            recordFailure(FibraEngineFailureStage.DEPLOYMENT, failure);
            cleanUnpreparedTransaction(transactionRoot, failure);
            throw failure;
        } catch (RuntimeException | IOException failure) {
            var wrapped = new FibraDeploymentException(FibraDeploymentErrorStage.COMMIT,
                packagePath, "cannot apply Fibra deployment", failure);
            recordFailure(FibraEngineFailureStage.DEPLOYMENT, wrapped);
            cleanUnpreparedTransaction(transactionRoot, wrapped);
            throw wrapped;
        }
    }

    private FibraDeploymentResult applyDeployment(InspectedDeploymentPackage deployment,
                                                  Path transactionRoot) {
        com.sstlfsj.fibra.loader.pf4j.FibraArtifactChange artifactChange = null;
        com.sstlfsj.fibra.loader.config.FibraConfigChange configChange = null;
        EngineTransactionJournal journal = null;
        try {
            var artifactWorkspace = Files.createDirectory(
                transactionRoot.resolve("artifacts"));
            var configWorkspace = Files.createDirectory(transactionRoot.resolve("config"));
            artifactChange = artifacts.prepareArtifacts(deployment.pluginPaths(),
                artifactWorkspace);
            configChange = config.prepareReplacement(deployment.configPath(), artifactChange,
                configWorkspace);
            var changed = artifactChange.changedArtifactIds();
            journal = EngineTransactionJournal.read(transactionRoot)
                .prepared(deployment.id(), deployment.version(), deployment.sha256(),
                    artifactJournal(changed, artifactWorkspace),
                    configJournal(configWorkspace));
            journal.write(transactionRoot);
            journal = journal.advance(EngineTransactionState.COMMITTING_ARTIFACTS);
            journal.write(transactionRoot);
            artifactChange.commit();
            journal = journal.advance(EngineTransactionState.COMMITTING_CONFIG);
            journal.write(transactionRoot);
            configChange.commit();
            journal = journal.advance(EngineTransactionState.VERIFYING);
            journal.write(transactionRoot);
            awaitRequiredEntries();
            var revision = currentRevision();
            journal = journal.committed(revision);
            journal.write(transactionRoot);
            AppliedRevisionStore.write(installedRoot, journal);
            synchronized (stateMonitor) {
                desiredRevision = revision;
                appliedRevision = revision;
                state = FibraEngineState.RUNNING;
            }
            configChange.complete();
            artifactChange.complete();
            deleteTree(transactionRoot);
            return new FibraDeploymentResult(deployment.id(), deployment.version(), revision,
                changed);
        } catch (RuntimeException | IOException failure) {
            var primary = failure instanceof FibraDeploymentException deploymentFailure
                ? deploymentFailure
                : new FibraDeploymentException(FibraDeploymentErrorStage.COMMIT,
                    deployment.workspace(), "deployment transaction failed", failure);
            if (journal != null && journal.state() == EngineTransactionState.COMMITTED) {
                throw primary;
            }
            var rollbackSucceeded = true;
            if (journal != null && journal.state() != EngineTransactionState.PREPARING) {
                try {
                    journal.rollingBack().write(transactionRoot);
                } catch (RuntimeException | IOException rollbackFailure) {
                    primary.addSuppressed(rollbackFailure);
                    rollbackSucceeded = false;
                }
            }
            if (configChange != null) {
                try {
                    configChange.rollback();
                } catch (RuntimeException rollbackFailure) {
                    primary.addSuppressed(rollbackFailure);
                    rollbackSucceeded = false;
                }
            }
            if (artifactChange != null) {
                try {
                    artifactChange.rollback();
                } catch (RuntimeException rollbackFailure) {
                    primary.addSuppressed(rollbackFailure);
                    rollbackSucceeded = false;
                }
            }
            if (rollbackSucceeded) {
                try {
                    deleteTree(transactionRoot);
                } catch (IOException cleanupFailure) {
                    primary.addSuppressed(cleanupFailure);
                }
            }
            throw primary;
        }
    }

    public FibraEngineStatus status() {
        synchronized (stateMonitor) {
            return new FibraEngineStatus(state, Optional.ofNullable(desiredRevision),
                Optional.ofNullable(appliedRevision), List.copyOf(failures.values()));
        }
    }

    public Context root() {
        return root;
    }

    public boolean isRunning() {
        synchronized (stateMonitor) {
            return state == FibraEngineState.RUNNING || state == FibraEngineState.DEGRADED;
        }
    }

    private void reconcile() {
        var revision = currentRevision();
        synchronized (stateMonitor) {
            desiredRevision = revision;
        }
        RuntimeException artifactFailure = null;
        try {
            var candidates = desiredArtifactCandidates();
            if (!candidates.isEmpty()) {
                artifacts.applyArtifacts(candidates);
            }
            clearFailure(FibraEngineFailureStage.ARTIFACT_RECONCILE);
        } catch (RuntimeException failure) {
            artifactFailure = failure;
            recordFailure(FibraEngineFailureStage.ARTIFACT_RECONCILE, failure);
        }
        RuntimeException configFailure = null;
        try {
            config.refresh();
            clearFailure(FibraEngineFailureStage.CONFIG_RECONCILE);
        } catch (RuntimeException failure) {
            configFailure = failure;
            recordFailure(FibraEngineFailureStage.CONFIG_RECONCILE, failure);
        }
        var convergedRevision = artifactFailure == null && configFailure == null
            ? currentRevision() : null;
        synchronized (stateMonitor) {
            state = artifactFailure == null && configFailure == null
                ? FibraEngineState.RUNNING : FibraEngineState.DEGRADED;
            if (convergedRevision != null) {
                desiredRevision = convergedRevision;
                appliedRevision = convergedRevision;
            }
        }
        if (artifactFailure != null) {
            throw artifactFailure;
        }
        if (configFailure != null) {
            throw configFailure;
        }
    }

    private List<Path> desiredArtifactCandidates() {
        if (artifactSourceRoot == null) {
            return List.of();
        }
        var versions = new DefaultVersionManager();
        var selected = new LinkedHashMap<String, Candidate>();
        try (var paths = Files.list(artifactSourceRoot)) {
            for (var path : paths.filter(Files::isRegularFile)
                .filter(candidate -> candidate.getFileName().toString().endsWith(".zip"))
                .sorted().toList()) {
                var descriptor = artifacts.inspectArtifact(path);
                selected.compute(descriptor.id(), (id, current) -> current == null
                    || versions.compareVersions(descriptor.version(),
                    current.descriptor.version()) > 0
                    ? new Candidate(path, descriptor) : current);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read artifact source", exception);
        }
        var installed = artifacts.catalog().artifacts().stream()
            .collect(java.util.stream.Collectors.toMap(FibraArtifactDescriptor::id,
                descriptor -> descriptor));
        return selected.values().stream().filter(candidate -> {
            var current = installed.get(candidate.descriptor.id());
            if (current == null) {
                return true;
            }
            var comparison = versions.compareVersions(candidate.descriptor.version(),
                current.version());
            return comparison > 0 || comparison == 0
                && !candidate.descriptor.sha256().equals(current.sha256());
        }).map(Candidate::path).sorted().toList();
    }

    private String currentRevision() {
        var effective = new LinkedHashMap<String, FibraArtifactDescriptor>();
        artifacts.catalog().artifacts().forEach(descriptor ->
            effective.put(descriptor.id(), descriptor));
        if (artifactSourceRoot != null) {
            var versions = new DefaultVersionManager();
            try (var paths = Files.list(artifactSourceRoot)) {
                for (var path : paths.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".zip"))
                    .sorted().toList()) {
                    var candidate = artifacts.inspectArtifact(path);
                    effective.compute(candidate.id(), (id, current) -> current == null
                        || versions.compareVersions(candidate.version(), current.version()) > 0
                        ? candidate : current);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("cannot read artifact source", exception);
            }
        }
        return EngineRevision.compute(effective.values().stream()
            .map(descriptor -> new RevisionArtifact(descriptor.id(), descriptor.version(),
                descriptor.sha256())).toList(), config.sourcePaths());
    }

    private void awaitRequiredEntries() {
        var startedAt = System.nanoTime();
        for (var entryId : requiredEntries) {
            var entry = config.resolve(entryId).orElseThrow(() ->
                new IllegalStateException("required entry is missing: " + entryId));
            var elapsed = Duration.ofNanos(Math.max(0, System.nanoTime() - startedAt));
            var remaining = readinessTimeout.minus(elapsed);
            if (remaining.isZero() || remaining.isNegative()) {
                throw new IllegalStateException(
                    "required entries exceeded readiness timeout: " + readinessTimeout);
            }
            entry.fibra().ready().block(remaining);
            if (entry.fibra().state() != FibraState.ACTIVE) {
                throw new IllegalStateException("required entry is not ACTIVE: " + entryId
                    + ", state=" + entry.fibra().state());
            }
        }
    }

    private void recordFailure(FibraEngineFailureStage stage, Throwable failure) {
        synchronized (stateMonitor) {
            failures.put(stage, new FibraEngineFailure(stage,
                Optional.ofNullable(desiredRevision), message(failure), Instant.now()));
            if (state == FibraEngineState.RUNNING) {
                state = FibraEngineState.DEGRADED;
            }
        }
    }

    private void clearFailure(FibraEngineFailureStage stage) {
        synchronized (stateMonitor) {
            failures.remove(stage);
        }
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getName()
            : failure.getMessage();
    }

    private void terminateAfterStartFailure() {
        closeResources();
        synchronized (stateMonitor) {
            state = FibraEngineState.TERMINATED;
        }
    }

    @Override
    public void close() {
        synchronized (stateMonitor) {
            if (state == FibraEngineState.TERMINATED) {
                return;
            }
            state = FibraEngineState.STOPPING;
        }
        RuntimeException failure = null;
        try {
            closeResources();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
            recordFailure(FibraEngineFailureStage.CLOSE, closeFailure);
        } finally {
            synchronized (stateMonitor) {
                state = FibraEngineState.TERMINATED;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void closeResources() {
        var failures = new ArrayList<Throwable>();
        close(artifactSource, failures);
        close(configSource, failures);
        close(reconcileCoordinator, failures);
        close(config, failures);
        close(artifacts, failures);
        try {
            root.closeAsync().block(rootCloseTimeout);
        } catch (RuntimeException failure) {
            failures.add(failure);
        }
        if (!failures.isEmpty()) {
            var close = new IllegalStateException("cannot close FibraEngine");
            failures.forEach(close::addSuppressed);
            throw close;
        }
    }

    private static void close(AutoCloseable resource, List<Throwable> failures) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception failure) {
            failures.add(failure);
        }
    }

    private List<EngineTransactionJournal.Artifact> artifactJournal(
        List<String> artifactIds, Path workspace) throws IOException {
        var result = new ArrayList<EngineTransactionJournal.Artifact>();
        for (var artifactId : artifactIds.stream().sorted().toList()) {
            var previous = installedRoot.resolve(artifactId);
            var next = workspace.resolve("next").resolve(artifactId);
            var oldExists = Files.isDirectory(previous, LinkOption.NOFOLLOW_LINKS);
            result.add(new EngineTransactionJournal.Artifact(artifactId, oldExists,
                oldExists ? EngineTransactionJournal.digest(previous) : null,
                EngineTransactionJournal.digest(next)));
        }
        return List.copyOf(result);
    }

    private List<EngineTransactionJournal.ConfigFile> configJournal(Path workspace)
        throws IOException {
        var nextRoot = workspace.resolve("next");
        if (!Files.isDirectory(nextRoot, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        var result = new ArrayList<EngineTransactionJournal.ConfigFile>();
        try (var paths = Files.walk(nextRoot)) {
            for (var next : paths.filter(path -> Files.isRegularFile(path,
                LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                var relative = nextRoot.relativize(next);
                var relativeName = relative.toString().replace('\\', '/');
                var previous = workspace.resolve("previous").resolve(relative);
                var oldExists = Files.isRegularFile(previous, LinkOption.NOFOLLOW_LINKS);
                result.add(new EngineTransactionJournal.ConfigFile(relativeName, oldExists,
                    oldExists ? EngineTransactionJournal.digest(previous) : null,
                    EngineTransactionJournal.digest(next)));
            }
        }
        return List.copyOf(result);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void cleanUnpreparedTransaction(Path root, RuntimeException primary) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            var journalPath = root.resolve(EngineTransactionJournal.FILE_NAME);
            if (!Files.isRegularFile(journalPath, LinkOption.NOFOLLOW_LINKS)
                || EngineTransactionJournal.read(root).state()
                == EngineTransactionState.PREPARING) {
                deleteTree(root);
            }
        } catch (IOException | RuntimeException cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
    }

    private void validateLayout() {
        if (artifactSourceRoot != null && (artifactSourceRoot.startsWith(installedRoot)
            || installedRoot.startsWith(artifactSourceRoot))) {
            throw new IllegalArgumentException(
                "artifact source and installed root must not contain each other");
        }
    }

    private record Candidate(Path path, FibraArtifactDescriptor descriptor) { }

    public static final class Builder {
        private final Path installedRoot;
        private final Path configLocation;
        private Path artifactSourceRoot;
        private Duration artifactDebounce = Duration.ofSeconds(1);
        private boolean configSourceEnabled;
        private Duration configDebounce = Duration.ofSeconds(1);
        private List<String> requiredEntries = List.of();
        private Duration readinessTimeout = Duration.ofSeconds(60);
        private Duration rootCloseTimeout = Duration.ofSeconds(30);
        private Duration resyncInterval = Duration.ofSeconds(30);
        private Duration retryInitial = Duration.ofMillis(250);
        private Duration retryMaximum = Duration.ofSeconds(30);

        private Builder(Path installedRoot, Path configLocation) {
            this.installedRoot = existingDirectory(installedRoot, "installedRoot");
            this.configLocation = existingFile(configLocation, "configLocation");
        }

        public Builder artifactSource(Path root, Duration debounce) {
            artifactSourceRoot = existingDirectory(root, "artifactSource");
            artifactDebounce = positive(debounce, "artifactDebounce");
            return this;
        }

        public Builder configSource(Duration debounce) {
            configSourceEnabled = true;
            configDebounce = positive(debounce, "configDebounce");
            return this;
        }

        public Builder requiredEntries(Collection<String> entries) {
            Objects.requireNonNull(entries, "entries");
            var unique = new java.util.LinkedHashSet<String>();
            for (var entry : entries) {
                if (entry == null || entry.isBlank()) {
                    throw new IllegalArgumentException("required entry must not be blank");
                }
                if (!unique.add(entry)) {
                    throw new IllegalArgumentException("duplicate required entry " + entry);
                }
            }
            requiredEntries = List.copyOf(unique);
            return this;
        }

        public Builder readinessTimeout(Duration value) {
            readinessTimeout = positive(value, "readinessTimeout");
            return this;
        }

        public Builder rootCloseTimeout(Duration value) {
            rootCloseTimeout = positive(value, "rootCloseTimeout");
            return this;
        }

        public Builder resyncInterval(Duration value) {
            resyncInterval = positive(value, "resyncInterval");
            return this;
        }

        public Builder retryBackoff(Duration initial, Duration maximum) {
            retryInitial = positive(initial, "retryInitial");
            retryMaximum = positive(maximum, "retryMaximum");
            if (retryMaximum.compareTo(retryInitial) < 0) {
                throw new IllegalArgumentException(
                    "retryMaximum must not be less than retryInitial");
            }
            return this;
        }

        public FibraEngine build() {
            return new FibraEngine(this);
        }

        private static Path existingDirectory(Path path, String name) {
            var normalized = Objects.requireNonNull(path, name).toAbsolutePath().normalize();
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(name + " must be an existing directory: "
                    + normalized);
            }
            return normalized;
        }

        private static Path existingFile(Path path, String name) {
            var normalized = Objects.requireNonNull(path, name).toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(name + " must be an existing file: "
                    + normalized);
            }
            return normalized;
        }

        private static Duration positive(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }
}
