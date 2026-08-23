package com.sstlfsj.fibra.loader.pf4j;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Fibra;
import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDescriptor;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** 把标准 PF4J 目录包生命周期桥接到可独立寻址的 Fibra 运行实例。 */
public final class FibraPluginLoader implements AutoCloseable {
    private final Context root;
    private final Path pluginsRoot;
    private final FibraDirectoryPluginManager pluginManager;
    private final PluginPackageInspector inspector = new PluginPackageInspector();
    private final PluginGraphPreflight preflight = new PluginGraphPreflight();
    private final LoaderOperationGate operationGate = new LoaderOperationGate();
    private final Map<String, MountedEntry> entries = new LinkedHashMap<>();

    private boolean initialized;

    public FibraPluginLoader(Context root, Path pluginsRoot) {
        this.root = Objects.requireNonNull(root, "root");
        if (root.root() != root) {
            throw new IllegalArgumentException("root must be the Fibra root Context");
        }
        this.pluginsRoot = validatePluginsRoot(pluginsRoot);
        new PluginCrashRecovery(this.pluginsRoot).recover();
        this.pluginManager = new FibraDirectoryPluginManager(this.pluginsRoot);
    }

    public List<String> loadArtifacts() {
        return runExclusive(() -> {
            var installed = installedPackages();
            preflight.validate(List.of(), installed);
            var installedIds = installed.stream()
                .map(plugin -> plugin.descriptor().getPluginId())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            var obsolete = pluginManager.getPlugins().stream()
                .map(PluginWrapper::getPluginId)
                .filter(id -> !installedIds.contains(id))
                .toList();
            if (!obsolete.isEmpty()) {
                disposeAffected(obsolete);
            }
            var missing = installed.stream()
                .filter(plugin -> pluginManager.getPlugin(
                    plugin.descriptor().getPluginId()) == null)
                .map(InspectedPluginPackage::packageRoot)
                .toList();
            pluginManager.loadPluginsStrict(missing);
            initialized = true;
            return artifactIdsInternal();
        });
    }

    public List<String> applyArtifacts(List<Path> candidatePaths) {
        requireInitialized();
        return runExclusive(() -> new PluginUpdateTransaction(this, pluginsRoot,
            inspector, preflight).apply(candidatePaths));
    }

    public Class<?> configType(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        return runInitialized(() -> configTypeInternal(pluginId));
    }

    public Fibra mount(PluginInstanceSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return runInitialized(() -> mountInternal(spec));
    }

    public Fibra update(String entryId, Object config) {
        Objects.requireNonNull(entryId, "entryId");
        return runInitialized(() -> updateInternal(entryId, ignored -> config, true));
    }

    public Fibra updateWithFactory(String entryId, PluginConfigFactory configFactory) {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(configFactory, "configFactory");
        return runInitialized(() -> updateInternal(entryId, configFactory, false));
    }

    public void unmount(String entryId) {
        Objects.requireNonNull(entryId, "entryId");
        runInitialized(() -> unmountInternal(entryId));
    }

    public void stopArtifact(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        runInitialized(() -> stopArtifactInternal(pluginId));
    }

    public boolean unloadArtifact(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        return runInitialized(() -> unloadArtifactInternal(pluginId));
    }

    public List<String> artifactIds() {
        return operationGate.snapshot().artifactIds();
    }

    public List<String> entryIds() {
        return operationGate.snapshot().entryIds();
    }

    public Optional<Fibra> fibra(String entryId) {
        Objects.requireNonNull(entryId, "entryId");
        return runInitialized(() -> Optional.ofNullable(entries.get(entryId))
            .map(MountedEntry::fibra));
    }

    /** 在制品更新和配置 reconcile 共用的可重入串行边界内执行。 */
    public <T> T runExclusive(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        return operationGate.runExclusive(action, this::identitySnapshotInternal);
    }

    /** 在制品更新和配置 reconcile 共用的可重入串行边界内执行。 */
    public void runExclusive(Runnable action) {
        Objects.requireNonNull(action, "action");
        runExclusive(() -> {
            action.run();
            return null;
        });
    }

    @Override
    public void close() {
        operationGate.close(() -> {
            var mounted = new ArrayList<>(entries.keySet());
            Collections.reverse(mounted);
            for (var entryId : mounted) {
                unmountInternal(entryId);
            }
            pluginManager.unloadPlugins();
        });
    }

    FibraPluginClassLoader pluginClassLoader(String pluginId) {
        return (FibraPluginClassLoader) pluginManager.getPluginClassLoader(pluginId);
    }

    ClassLoader entrypointClassLoader(String entryId) {
        var entry = entries.get(entryId);
        return entry == null ? null : entry.entrypointClassLoader();
    }

    FibraPluginCandidate inspectCandidate(Path candidatePath) {
        Objects.requireNonNull(candidatePath, "candidatePath");
        var inspectRoot = pluginsRoot.resolve(PluginCrashRecovery.PREFLIGHT_DIRECTORY)
            .resolve("inspect-" + java.util.UUID.randomUUID());
        try {
            var inspected = inspector.inspectCandidate(candidatePath, inspectRoot);
            return new FibraPluginCandidate(inspected.descriptor().getPluginId(),
                inspected.descriptor().getVersion(), Files.getLastModifiedTime(candidatePath));
        } catch (IOException exception) {
            throw new FibraArtifactException(FibraArtifactErrorStage.READ,
                List.of(candidatePath), List.of(), "cannot inspect plugin candidate", exception);
        } finally {
            try {
                PluginCrashRecovery.deleteTree(inspectRoot);
            } catch (IOException ignored) {
                // 构造期会安全清理只读预检垃圾。
            }
        }
    }

    String currentPluginVersion(String pluginId) {
        return operationGate.snapshot().artifactVersions().get(pluginId);
    }

    List<InspectedPluginPackage> installedPackages() {
        try (var paths = Files.list(pluginsRoot)) {
            var packages = new ArrayList<InspectedPluginPackage>();
            for (var path : paths
                .filter(candidate -> Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS))
                .filter(candidate -> !candidate.getFileName().toString().startsWith("."))
                .sorted(Comparator.comparing(candidate -> candidate.getFileName().toString()))
                .toList()) {
                packages.add(inspector.inspectDirectory(path));
            }
            return List.copyOf(packages);
        } catch (IOException exception) {
            throw new FibraArtifactException(FibraArtifactErrorStage.READ,
                List.of(pluginsRoot), List.of(), "cannot inspect installed plugin graph",
                exception);
        }
    }

    RuntimeSnapshot snapshotRuntime(List<String> affectedArtifactIds) {
        var affected = Set.copyOf(affectedArtifactIds);
        var started = dependencyFirst(affected).stream()
            .filter(id -> pluginManager.getPlugin(id).getPluginState() == PluginState.STARTED)
            .toList();
        var specs = entries.values().stream()
            .map(MountedEntry::spec)
            .filter(spec -> affected.contains(spec.pluginId()))
            .toList();
        return new RuntimeSnapshot(affectedArtifactIds, started, specs);
    }

    void disposeAffected(List<String> affectedArtifactIds) {
        var affected = Set.copyOf(affectedArtifactIds);
        var order = dependentFirst(affected);
        for (var pluginId : order) {
            unmountEntriesForArtifact(pluginId);
            var plugin = pluginManager.getPlugin(pluginId);
            if (plugin != null && plugin.getPluginState() == PluginState.STARTED) {
                pluginManager.stopPlugin(pluginId);
            }
        }
        pluginManager.unloadPluginsStrict(order.stream()
            .filter(id -> pluginManager.getPlugin(id) != null).toList());
    }

    void loadInstalledAffected(List<String> affectedArtifactIds) {
        var affected = Set.copyOf(affectedArtifactIds);
        var paths = installedPackages().stream()
            .filter(plugin -> affected.contains(plugin.descriptor().getPluginId()))
            .filter(plugin -> pluginManager.getPlugin(
                plugin.descriptor().getPluginId()) == null)
            .map(InspectedPluginPackage::packageRoot)
            .toList();
        pluginManager.loadPluginsStrict(paths);
    }

    void restoreRuntime(RuntimeSnapshot snapshot) {
        for (var pluginId : dependencyFirst(Set.copyOf(snapshot.startedArtifactIds()))) {
            if (snapshot.startedArtifactIds().contains(pluginId)) {
                startArtifactInternal(pluginId);
            }
        }
        for (var spec : snapshot.instanceSpecs()) {
            mountInternal(spec);
        }
    }

    private Fibra mountInternal(PluginInstanceSpec spec) {
        if (entries.containsKey(spec.entryId())) {
            throw new IllegalArgumentException("duplicate plugin entry " + spec.entryId());
        }
        if (pluginManager.getPlugin(spec.pluginId()) == null) {
            throw new IllegalArgumentException("unknown plugin " + spec.pluginId());
        }
        if (spec.parentContext().root() != root) {
            throw new IllegalArgumentException(
                "parentContext must belong to this Fibra root Context");
        }

        var startedBefore = startedArtifactIds();
        startArtifactInternal(spec.pluginId());
        Fibra fibra = null;
        try {
            var entrypoint = newEntrypointInternal(spec.pluginId());
            var config = createConfig(spec, entrypoint.configType());
            var descriptor = descriptor(entrypoint, spec.entryId())
                .withRequirements(spec.requirements());
            if (!descriptor.name().equals(spec.entryId())) {
                throw new IllegalStateException("plugin " + spec.pluginId()
                    + " descriptor name must equal entryId " + spec.entryId());
            }
            fibra = createFibra(spec, entrypoint, descriptor, config);
            fibra.await().block();
            entries.put(spec.entryId(), new MountedEntry(spec, fibra,
                entrypoint.getClass().getClassLoader()));
            return fibra;
        } catch (RuntimeException failure) {
            if (fibra != null) {
                try {
                    fibra.dispose().block();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            stopNewlyStartedArtifacts(startedBefore, failure);
            throw failure;
        }
    }

    private Fibra updateInternal(String entryId, PluginConfigFactory configFactory,
                                 boolean constantConfig) {
        var mounted = requireEntry(entryId);
        var entrypoint = newEntrypointInternal(mounted.spec().pluginId());
        var nextSpec = mounted.spec().withConfigFactory(configFactory, constantConfig);
        var config = createConfig(nextSpec, entrypoint.configType());
        var fibra = mounted.fibra().update(config).block();
        entries.put(entryId, new MountedEntry(nextSpec, fibra,
            entrypoint.getClass().getClassLoader()));
        return fibra;
    }

    private void unmountInternal(String entryId) {
        var mounted = entries.remove(entryId);
        if (mounted != null) {
            mounted.fibra().dispose().block();
        }
    }

    private void stopArtifactInternal(String pluginId) {
        if (pluginManager.getPlugin(pluginId) == null) {
            throw new IllegalArgumentException("unknown plugin " + pluginId);
        }
        var affected = dependentClosure(pluginId);
        for (var affectedId : dependentFirst(affected)) {
            unmountEntriesForArtifact(affectedId);
            var plugin = pluginManager.getPlugin(affectedId);
            if (plugin != null && plugin.getPluginState() == PluginState.STARTED) {
                pluginManager.stopPlugin(affectedId);
            }
        }
    }

    private boolean unloadArtifactInternal(String pluginId) {
        if (pluginManager.getPlugin(pluginId) == null) {
            return false;
        }
        var affected = dependentClosure(pluginId);
        disposeAffected(List.copyOf(affected));
        return true;
    }

    private void startArtifactInternal(String pluginId) {
        var state = pluginManager.startPlugin(pluginId);
        if (state != PluginState.STARTED) {
            throw new IllegalStateException("PF4J plugin did not start: " + pluginId
                + " (state=" + state + ")");
        }
    }

    private Class<?> configTypeInternal(String pluginId) {
        var startedBefore = startedArtifactIds();
        Class<?> type;
        try {
            type = Objects.requireNonNull(newEntrypointInternal(pluginId).configType(),
                "entrypoint configType returned null");
        } catch (RuntimeException failure) {
            stopNewlyStartedArtifacts(startedBefore, failure);
            throw failure;
        }
        stopNewlyStartedArtifacts(startedBefore);
        return type;
    }

    private FibraPluginEntrypoint<?> newEntrypointInternal(String pluginId) {
        var plugin = pluginManager.getPlugin(pluginId);
        if (plugin == null) {
            throw new IllegalArgumentException("unknown plugin " + pluginId);
        }
        startArtifactInternal(pluginId);
        var installed = inspector.inspectDirectory(plugin.getPluginPath());
        var classNames = installed.entrypointClassNames();
        if (classNames.size() != 1) {
            throw new IllegalStateException("plugin " + pluginId
                + " must provide exactly one FibraPluginEntrypoint, found "
                + classNames.size());
        }
        try {
            var type = Class.forName(classNames.getFirst(), true,
                pluginManager.getPluginClassLoader(pluginId));
            return (FibraPluginEntrypoint<?>) type.getConstructor().newInstance();
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException
                 | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("cannot create FibraPluginEntrypoint for "
                + pluginId, exception);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PluginDescriptor<Object> descriptor(FibraPluginEntrypoint entrypoint,
                                                        String entryId) {
        return (PluginDescriptor<Object>) Objects.requireNonNull(
            entrypoint.descriptor(entryId), "entrypoint descriptor returned null");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Fibra createFibra(PluginInstanceSpec spec,
                                     FibraPluginEntrypoint entrypoint,
                                     PluginDescriptor<Object> descriptor,
                                     Object config) {
        var plugin = (Plugin<Object>) Objects.requireNonNull(
            entrypoint.create(spec.entryId()), "entrypoint plugin returned null");
        return spec.parentContext().plugin(descriptor, plugin, config);
    }

    private Object createConfig(PluginInstanceSpec spec, Class<?> configType) {
        var type = Objects.requireNonNull(configType, "entrypoint configType returned null");
        var config = spec.configFactory().create(type);
        validateConfig(type, config, spec.pluginId());
        if (spec.constantConfig() && config != null
            && type.getClassLoader() == pluginClassLoader(spec.pluginId())) {
            throw new IllegalArgumentException("plugin " + spec.pluginId()
                + " uses a plugin-private config type; configure it with configFactory");
        }
        return config;
    }

    private static void validateConfig(Class<?> configType, Object config, String pluginId) {
        if (configType == Void.class) {
            if (config != null) {
                throw new IllegalArgumentException("plugin " + pluginId
                    + " requires null config");
            }
        } else if (config == null || !configType.isInstance(config)) {
            throw new IllegalArgumentException("plugin " + pluginId + " config must be a "
                + configType.getName());
        }
    }

    private MountedEntry requireEntry(String entryId) {
        var mounted = entries.get(entryId);
        if (mounted == null) {
            throw new IllegalArgumentException("unknown plugin entry " + entryId);
        }
        return mounted;
    }

    private void stopNewlyStartedArtifacts(Set<String> startedBefore, RuntimeException failure) {
        var started = new ArrayList<>(pluginManager.getStartedPlugins());
        Collections.reverse(started);
        for (var plugin : started) {
            if (startedBefore.contains(plugin.getPluginId()) || hasEntries(plugin.getPluginId())) {
                continue;
            }
            try {
                pluginManager.stopPlugin(plugin.getPluginId());
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private void stopNewlyStartedArtifacts(Set<String> startedBefore) {
        var failure = new IllegalStateException(
            "cannot restore artifact states after reading config type");
        stopNewlyStartedArtifacts(startedBefore, failure);
        if (failure.getSuppressed().length > 0) {
            throw failure;
        }
    }

    private boolean hasEntries(String pluginId) {
        return entries.values().stream().anyMatch(entry ->
            entry.spec().pluginId().equals(pluginId));
    }

    private void unmountEntriesForArtifact(String pluginId) {
        var ids = entries.entrySet().stream()
            .filter(entry -> entry.getValue().spec().pluginId().equals(pluginId))
            .map(Map.Entry::getKey)
            .toList();
        for (int index = ids.size() - 1; index >= 0; index--) {
            unmountInternal(ids.get(index));
        }
    }

    private Set<String> dependentClosure(String pluginId) {
        var result = new LinkedHashSet<String>();
        collectDependents(pluginId, result);
        return result;
    }

    private void collectDependents(String pluginId, Set<String> result) {
        if (!result.add(pluginId)) {
            return;
        }
        pluginManager.getPlugins().stream()
            .sorted(Comparator.comparing(PluginWrapper::getPluginId))
            .filter(plugin -> plugin.getDescriptor().getDependencies().stream()
                .anyMatch(dependency -> dependency.getPluginId().equals(pluginId)))
            .forEach(plugin -> collectDependents(plugin.getPluginId(), result));
    }

    private List<String> dependentFirst(Set<String> affected) {
        var result = new ArrayList<String>();
        var visited = new LinkedHashSet<String>();
        for (var pluginId : affected.stream().sorted().toList()) {
            collectDependentFirst(pluginId, affected, visited, result);
        }
        return result;
    }

    private void collectDependentFirst(String pluginId, Set<String> affected,
                                       Set<String> visited, List<String> result) {
        if (!affected.contains(pluginId) || !visited.add(pluginId)) {
            return;
        }
        pluginManager.getPlugins().stream()
            .sorted(Comparator.comparing(PluginWrapper::getPluginId))
            .filter(plugin -> affected.contains(plugin.getPluginId()))
            .filter(plugin -> plugin.getDescriptor().getDependencies().stream()
                .anyMatch(dependency -> dependency.getPluginId().equals(pluginId)))
            .forEach(plugin -> collectDependentFirst(plugin.getPluginId(), affected,
                visited, result));
        result.add(pluginId);
    }

    private List<String> dependencyFirst(Set<String> affected) {
        var result = new ArrayList<String>();
        var visited = new LinkedHashSet<String>();
        for (var pluginId : affected.stream().sorted().toList()) {
            collectDependencyFirst(pluginId, affected, visited, result);
        }
        return result;
    }

    private void collectDependencyFirst(String pluginId, Set<String> affected,
                                        Set<String> visited, List<String> result) {
        if (!affected.contains(pluginId) || !visited.add(pluginId)) {
            return;
        }
        var plugin = pluginManager.getPlugin(pluginId);
        if (plugin != null) {
            plugin.getDescriptor().getDependencies().stream()
                .map(dependency -> dependency.getPluginId())
                .filter(affected::contains)
                .sorted()
                .forEach(dependency -> collectDependencyFirst(dependency, affected,
                    visited, result));
            result.add(pluginId);
        }
    }

    private Set<String> startedArtifactIds() {
        var result = new LinkedHashSet<String>();
        for (var plugin : pluginManager.getStartedPlugins()) {
            result.add(plugin.getPluginId());
        }
        return result;
    }

    private List<String> artifactIdsInternal() {
        return pluginManager.getPlugins().stream()
            .map(PluginWrapper::getPluginId)
            .sorted()
            .toList();
    }

    private LoaderOperationGate.IdentitySnapshot identitySnapshotInternal() {
        var versions = new LinkedHashMap<String, String>();
        pluginManager.getPlugins().stream()
            .sorted(Comparator.comparing(PluginWrapper::getPluginId))
            .forEach(plugin -> versions.put(plugin.getPluginId(),
                plugin.getDescriptor().getVersion()));
        return new LoaderOperationGate.IdentitySnapshot(versions,
            List.copyOf(entries.keySet()));
    }

    private <T> T runInitialized(Supplier<T> action) {
        return runExclusive(() -> {
            requireInitialized();
            return action.get();
        });
    }

    private void runInitialized(Runnable action) {
        runInitialized(() -> {
            action.run();
            return null;
        });
    }

    private void requireInitialized() {
        if (!initialized) {
            throw new IllegalStateException(
                "FibraPluginLoader.loadArtifacts() must complete before management operations");
        }
    }

    private static Path validatePluginsRoot(Path path) {
        Objects.requireNonNull(path, "pluginsRoot");
        var normalized = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("pluginsRoot must be an existing directory: "
                + normalized);
        }
        return normalized;
    }

    record RuntimeSnapshot(List<String> affectedArtifactIds,
                           List<String> startedArtifactIds,
                           List<PluginInstanceSpec> instanceSpecs) {
        RuntimeSnapshot {
            affectedArtifactIds = List.copyOf(affectedArtifactIds);
            startedArtifactIds = List.copyOf(startedArtifactIds);
            instanceSpecs = List.copyOf(instanceSpecs);
        }
    }

    private record MountedEntry(PluginInstanceSpec spec, Fibra fibra,
                                ClassLoader entrypointClassLoader) {
    }
}
