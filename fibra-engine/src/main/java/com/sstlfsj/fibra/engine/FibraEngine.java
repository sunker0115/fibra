package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.FibraState;
import com.sstlfsj.fibra.loader.config.FibraConfigErrorStage;
import com.sstlfsj.fibra.loader.config.FibraConfigException;
import com.sstlfsj.fibra.loader.config.FibraConfigLoader;
import com.sstlfsj.fibra.loader.pf4j.FibraArtifactDescriptor;
import com.sstlfsj.fibra.loader.pf4j.FibraArtifactErrorStage;
import com.sstlfsj.fibra.loader.pf4j.FibraArtifactException;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.pf4j.DefaultVersionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOGGER = LoggerFactory.getLogger(FibraEngine.class);

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
    private String desiredArtifactRevision;
    private String desiredConfigRevision;
    private String appliedArtifactRevision;
    private String appliedConfigRevision;
    private String desiredRevision;
    private String appliedRevision;
    private boolean mutationBlocked;
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
            var initialConfig = config.load();
            var initialArtifactRevision = currentArtifactRevision();
            var initialConfigRevision = EngineRevision.config(initialConfig);
            synchronized (stateMonitor) {
                desiredArtifactRevision = initialArtifactRevision;
                appliedArtifactRevision = initialArtifactRevision;
                desiredConfigRevision = initialConfigRevision;
                appliedConfigRevision = initialConfigRevision;
                publishRevisions();
            }
            stage = FibraEngineFailureStage.READINESS;
            awaitRequiredEntries();
            stage = FibraEngineFailureStage.STARTUP;
            reconcileCoordinator = new ReconcileCoordinator(this::reconcile, resyncInterval,
                retryInitial, retryMaximum, ignored -> { });
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
            LOGGER.atInfo()
                .log("event=fibra.engine.started appliedRevision={}", appliedRevision);
            reconcileCoordinator.request();
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
            requireMutationsAllowedLocked();
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
            requireMutationsAllowedLocked();
            current = reconcileCoordinator;
        }
        return current.execute(() -> applyDeploymentSerialized(packagePath));
    }

    private FibraDeploymentResult applyDeploymentSerialized(Path packagePath) {
        synchronized (stateMonitor) {
            requireMutationsAllowedLocked();
        }
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
            cleanUnpreparedTransaction(transactionRoot, failure);
            throw failure;
        } catch (RuntimeException | IOException failure) {
            var wrapped = new FibraDeploymentException(FibraDeploymentErrorStage.COMMIT,
                packagePath, "cannot apply Fibra deployment", failure);
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
            var artifactRevision = currentArtifactRevision();
            var configRevision = EngineRevision.config(config.snapshot());
            var revision = EngineRevision.combine(artifactRevision, configRevision);
            var committed = journal.committed(revision);
            committed.write(transactionRoot);
            journal = committed;
            synchronized (stateMonitor) {
                desiredArtifactRevision = artifactRevision;
                appliedArtifactRevision = artifactRevision;
                desiredConfigRevision = configRevision;
                appliedConfigRevision = configRevision;
                desiredRevision = revision;
                appliedRevision = revision;
            }
            var result = new FibraDeploymentResult(deployment.id(), deployment.version(), revision,
                changed);
            LOGGER.atInfo()
                .log("event=fibra.engine.deployment.committed deploymentId={} "
                        + "deploymentVersion={} transactionId={} appliedRevision={}",
                    deployment.id(), deployment.version(), journal.transactionId(), revision);
            completeCommittedDeployment(transactionRoot, journal, configChange,
                artifactChange);
            return result;
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
            } else {
                var rollback = new FibraDeploymentException(
                    FibraDeploymentErrorStage.ROLLBACK, deployment.workspace(),
                    "cannot fully roll back Fibra deployment", primary);
                blockMutationsAfterIncompleteRollback(
                    FibraEngineFailureStage.DEPLOYMENT, rollback);
                throw rollback;
            }
            throw primary;
        }
    }

    private void completeCommittedDeployment(Path transactionRoot,
                                             EngineTransactionJournal journal,
                                             com.sstlfsj.fibra.loader.config.FibraConfigChange
                                                 configChange,
                                             com.sstlfsj.fibra.loader.pf4j.FibraArtifactChange
                                                 artifactChange) {
        var failures = new ArrayList<Throwable>();
        try {
            AppliedRevisionStore.write(installedRoot, journal);
        } catch (IOException | RuntimeException failure) {
            failures.add(failure);
        }
        try {
            configChange.complete();
        } catch (RuntimeException failure) {
            failures.add(failure);
        }
        try {
            artifactChange.complete();
        } catch (RuntimeException failure) {
            failures.add(failure);
        }
        if (failures.isEmpty()) {
            try {
                deleteTree(transactionRoot);
            } catch (IOException failure) {
                failures.add(failure);
            }
        }
        if (!failures.isEmpty()) {
            var cleanup = new IllegalStateException(
                "committed Fibra deployment requires recovery cleanup");
            failures.forEach(cleanup::addSuppressed);
            LOGGER.atWarn()
                .setCause(cleanup)
                .log("event=fibra.engine.deployment.cleanup_deferred deploymentId={} "
                        + "deploymentVersion={} transactionId={}",
                    journal.deploymentId(), journal.deploymentVersion(), journal.transactionId());
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
        synchronized (stateMonitor) {
            if (mutationBlocked) {
                return;
            }
        }
        RuntimeException artifactFailure = null;
        try {
            var target = desiredArtifactTarget();
            setDesiredArtifactRevision(target.revision());
            if (!target.candidates().isEmpty()) {
                artifacts.applyArtifacts(target.candidates());
            }
            setAppliedArtifactRevision(currentArtifactRevision());
            clearFailure(FibraEngineFailureStage.ARTIFACT_RECONCILE);
        } catch (RuntimeException failure) {
            artifactFailure = failure;
            setDesiredArtifactRevisionIfReadable();
            if (failure instanceof FibraArtifactException artifactException
                && artifactException.stage() == FibraArtifactErrorStage.ROLLBACK) {
                blockMutationsAfterIncompleteRollback(
                    FibraEngineFailureStage.ARTIFACT_RECONCILE, failure);
                refreshOperationalState();
                throw failure;
            }
            recordFailure(FibraEngineFailureStage.ARTIFACT_RECONCILE, failure);
        }
        RuntimeException configFailure = null;
        try {
            var snapshot = config.refresh();
            var revision = EngineRevision.config(snapshot);
            setDesiredConfigRevision(revision);
            setAppliedConfigRevision(revision);
            clearFailure(FibraEngineFailureStage.CONFIG_RECONCILE);
        } catch (RuntimeException failure) {
            configFailure = failure;
            setDesiredConfigRevisionIfReadable();
            if (failure instanceof FibraConfigException configException
                && configException.stage() == FibraConfigErrorStage.ROLLBACK) {
                blockMutationsAfterIncompleteRollback(
                    FibraEngineFailureStage.CONFIG_RECONCILE, failure);
            } else {
                recordFailure(FibraEngineFailureStage.CONFIG_RECONCILE, failure);
            }
        }
        refreshOperationalState();
        if (artifactFailure != null) {
            throw artifactFailure;
        }
        if (configFailure != null) {
            throw configFailure;
        }
    }

    private ArtifactTarget desiredArtifactTarget() {
        if (artifactSourceRoot == null) {
            return new ArtifactTarget(List.of(), currentArtifactRevision());
        }
        var versions = new DefaultVersionManager();
        var grouped = new LinkedHashMap<String, List<Candidate>>();
        try (var paths = Files.list(artifactSourceRoot)) {
            for (var path : paths.filter(Files::isRegularFile)
                .filter(candidate -> candidate.getFileName().toString().endsWith(".zip"))
                .sorted().toList()) {
                var descriptor = artifacts.inspectArtifact(path);
                grouped.computeIfAbsent(descriptor.id(), ignored -> new ArrayList<>())
                    .add(new Candidate(path, descriptor));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read artifact source", exception);
        }
        var selected = new LinkedHashMap<String, Candidate>();
        grouped.forEach((id, candidates) -> selected.put(id,
            selectHighestArtifactCandidate(id, candidates, versions)));
        var installed = artifacts.catalog().artifacts().stream()
            .collect(java.util.stream.Collectors.toMap(FibraArtifactDescriptor::id,
                descriptor -> descriptor));
        var candidates = selected.values().stream().filter(candidate -> {
            var current = installed.get(candidate.descriptor.id());
            if (current == null) {
                return true;
            }
            var comparison = versions.compareVersions(candidate.descriptor.version(),
                current.version());
            return comparison > 0 || comparison == 0
                && !candidate.descriptor.sha256().equals(current.sha256());
        }).map(Candidate::path).sorted().toList();
        selected.values().forEach(candidate -> {
            var current = installed.get(candidate.descriptor.id());
            if (current == null || versions.compareVersions(candidate.descriptor.version(),
                current.version()) >= 0) {
                installed.put(candidate.descriptor.id(), candidate.descriptor);
            }
        });
        return new ArtifactTarget(candidates, artifactRevision(installed.values()));
    }

    private static Candidate selectHighestArtifactCandidate(
        String artifactId, List<Candidate> candidates, DefaultVersionManager versions) {
        var highestVersion = candidates.stream().map(candidate -> candidate.descriptor.version())
            .max(versions::compareVersions).orElseThrow();
        var highest = candidates.stream().filter(candidate ->
            versions.compareVersions(candidate.descriptor.version(), highestVersion) == 0)
            .toList();
        var digests = highest.stream().map(candidate -> candidate.descriptor.sha256())
            .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        if (digests.size() > 1) {
            throw new IllegalStateException("conflicting artifact candidates for pluginId="
                + artifactId + ", version=" + highestVersion + ", sha256=" + digests);
        }
        return highest.getFirst();
    }

    private String currentArtifactRevision() {
        return artifactRevision(artifacts.catalog().artifacts());
    }

    private static String artifactRevision(Collection<FibraArtifactDescriptor> descriptors) {
        return EngineRevision.artifacts(descriptors.stream()
            .map(descriptor -> new RevisionArtifact(descriptor.id(), descriptor.version(),
                descriptor.sha256())).toList());
    }

    private void setDesiredArtifactRevisionIfReadable() {
        if (artifactSourceRoot == null) {
            return;
        }
        try (var paths = Files.list(artifactSourceRoot)) {
            setDesiredArtifactRevision(EngineRevision.sourceFiles(paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".zip"))
                .toList()));
        } catch (IOException | RuntimeException ignored) {
            // 原始 reconcile 失败保留为主诊断。
        }
    }

    private void setDesiredConfigRevisionIfReadable() {
        try {
            setDesiredConfigRevision(EngineRevision.sourceFiles(config.sourcePaths()));
        } catch (RuntimeException ignored) {
            // 原始 reconcile 失败保留为主诊断。
        }
    }

    private void setDesiredArtifactRevision(String revision) {
        synchronized (stateMonitor) {
            desiredArtifactRevision = revision;
            publishRevisions();
        }
    }

    private void setAppliedArtifactRevision(String revision) {
        synchronized (stateMonitor) {
            appliedArtifactRevision = revision;
            publishRevisions();
        }
    }

    private void setDesiredConfigRevision(String revision) {
        synchronized (stateMonitor) {
            desiredConfigRevision = revision;
            publishRevisions();
        }
    }

    private void setAppliedConfigRevision(String revision) {
        synchronized (stateMonitor) {
            appliedConfigRevision = revision;
            publishRevisions();
        }
    }

    private void publishRevisions() {
        if (desiredArtifactRevision != null && desiredConfigRevision != null) {
            desiredRevision = EngineRevision.combine(desiredArtifactRevision,
                desiredConfigRevision);
        }
        if (appliedArtifactRevision != null && appliedConfigRevision != null) {
            appliedRevision = EngineRevision.combine(appliedArtifactRevision,
                appliedConfigRevision);
        }
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
        FibraEngineFailure recorded;
        boolean report;
        synchronized (stateMonitor) {
            recorded = new FibraEngineFailure(stage,
                Optional.ofNullable(desiredRevision), message(failure), Instant.now());
            var previous = failures.put(stage, recorded);
            var asynchronous = state == FibraEngineState.RUNNING
                || state == FibraEngineState.DEGRADED;
            report = asynchronous && (previous == null
                || !previous.revision().equals(recorded.revision())
                || !previous.message().equals(recorded.message()));
            if (state == FibraEngineState.RUNNING) {
                state = FibraEngineState.DEGRADED;
            }
        }
        if (report) {
            LOGGER.atWarn()
                .setCause(failure)
                .log("event=fibra.engine.reconcile.failed stage={} desiredRevision={}",
                    stage, recorded.revision().orElse("unavailable"));
        }
    }

    private void clearFailure(FibraEngineFailureStage stage) {
        FibraEngineFailure recovered;
        String revision;
        synchronized (stateMonitor) {
            recovered = failures.remove(stage);
            refreshOperationalStateLocked();
            revision = appliedRevision;
        }
        if (recovered != null) {
            LOGGER.atInfo()
                .log("event=fibra.engine.reconcile.recovered stage={} appliedRevision={}",
                    stage, revision == null ? "unavailable" : revision);
        }
    }

    private void requireMutationsAllowedLocked() {
        if (mutationBlocked) {
            throw new IllegalStateException(
                "FibraEngine mutations are blocked after an incomplete rollback");
        }
    }

    private void blockMutationsAfterIncompleteRollback(
        FibraEngineFailureStage stage, Throwable failure) {
        boolean report;
        String revision;
        synchronized (stateMonitor) {
            report = !mutationBlocked;
            mutationBlocked = true;
            revision = desiredRevision;
            failures.put(stage, new FibraEngineFailure(stage,
                Optional.ofNullable(desiredRevision), message(failure), Instant.now()));
            if (state == FibraEngineState.RUNNING) {
                state = FibraEngineState.DEGRADED;
            }
        }
        if (report) {
            LOGGER.atError()
                .setCause(failure)
                .log("event=fibra.engine.mutation.blocked stage={} desiredRevision={}",
                    stage, revision == null ? "unavailable" : revision);
        }
    }

    private void refreshOperationalState() {
        synchronized (stateMonitor) {
            refreshOperationalStateLocked();
        }
    }

    private void refreshOperationalStateLocked() {
        if (state == FibraEngineState.RUNNING || state == FibraEngineState.DEGRADED) {
            state = failures.isEmpty() ? FibraEngineState.RUNNING
                : FibraEngineState.DEGRADED;
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
        boolean reportStopped;
        synchronized (stateMonitor) {
            if (state == FibraEngineState.TERMINATED) {
                return;
            }
            reportStopped = state != FibraEngineState.NEW;
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
            if (reportStopped) {
                LOGGER.atInfo()
                    .log("event=fibra.engine.stopped appliedRevision={}",
                        appliedRevision == null ? "unavailable" : appliedRevision);
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

    private record ArtifactTarget(List<Path> candidates, String revision) {
        private ArtifactTarget {
            candidates = List.copyOf(candidates);
            Objects.requireNonNull(revision, "revision");
        }
    }

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
