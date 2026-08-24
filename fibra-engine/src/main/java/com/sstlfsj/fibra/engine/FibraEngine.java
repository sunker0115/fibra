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
    private FibraReconcileController controller;
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
            stage = FibraEngineFailureStage.READINESS;
            awaitRequiredEntries();
            stage = FibraEngineFailureStage.STARTUP;
            controller = new FibraReconcileController(this::reconcile, resyncInterval,
                retryInitial, retryMaximum,
                failure -> recordFailure(FibraEngineFailureStage.CONFIG_RECONCILE,
                    failure));
            if (artifactSourceRoot != null) {
                artifactSource = new ArtifactDirectorySource(artifactSourceRoot,
                    artifactDebounce, controller::request);
            }
            if (configSourceEnabled) {
                configSource = new ConfigFileSource(config::sourcePaths, configDebounce,
                    controller::request);
            }
            controller.start();
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
            terminateAfterStartFailure();
            throw failure;
        }
    }

    public void requestReconcile() {
        FibraReconcileController current;
        synchronized (stateMonitor) {
            if (state != FibraEngineState.RUNNING
                && state != FibraEngineState.DEGRADED) {
                throw new IllegalStateException("FibraEngine is not running");
            }
            current = controller;
        }
        current.request();
    }

    public FibraDeploymentResult applyDeployment(Path packagePath) {
        Objects.requireNonNull(packagePath, "packagePath");
        throw new UnsupportedOperationException("deployment support is not initialized");
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
        synchronized (stateMonitor) {
            state = artifactFailure == null && configFailure == null
                ? FibraEngineState.RUNNING : FibraEngineState.DEGRADED;
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

    private void awaitRequiredEntries() {
        for (var entryId : requiredEntries) {
            var entry = config.resolve(entryId).orElseThrow(() ->
                new IllegalStateException("required entry is missing: " + entryId));
            entry.fibra().ready().block(readinessTimeout);
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
        close(controller, failures);
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
