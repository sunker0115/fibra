package com.sstlfsj.fibra.loader.pf4j;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Fibra;
import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDescriptor;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import org.pf4j.DefaultVersionManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.pf4j.VersionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.jar.JarFile;

/** 把 PF4J JAR 制品生命周期桥接到可独立寻址的 Fibra 运行实例。 */
public final class FibraPluginLoader implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(FibraPluginLoader.class);
    private static final VersionManager VERSIONS = new DefaultVersionManager();
    private static final List<String> FORBIDDEN_ARTIFACT_PREFIXES = List.of(
        "com/sstlfsj/fibra/",
        "org/pf4j/",
        "org/reactivestreams/",
        "reactor/",
        "org/slf4j/"
    );

    private final Context root;
    private final Path pluginsRoot;
    private final FibraJarPluginManager pluginManager;
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final Map<String, MountedEntry> entries = new LinkedHashMap<>();
    private boolean closed;

    public FibraPluginLoader(Context root, Path pluginsRoot) {
        this.root = Objects.requireNonNull(root, "root");
        if (root.root() != root) {
            throw new IllegalArgumentException("root must be the Fibra root Context");
        }
        this.pluginsRoot = validatePluginsRoot(pluginsRoot);
        this.pluginManager = new FibraJarPluginManager(this.pluginsRoot);
    }

    public List<String> loadArtifacts() {
        return runExclusive(() -> {
            var loadedPaths = new LinkedHashSet<Path>();
            for (var plugin : pluginManager.getPlugins()) {
                loadedPaths.add(normalize(plugin.getPluginPath()));
            }
            try (var paths = Files.list(pluginsRoot)) {
                var additions = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .map(FibraPluginLoader::normalize)
                    .sorted()
                    .filter(path -> !loadedPaths.contains(path))
                    .toList();
                for (var path : additions) {
                    requireDirectChild(path);
                    validateArtifact(path);
                }
                pluginManager.loadPluginsStrict(additions);
            } catch (IOException exception) {
                throw new IllegalStateException("cannot list plugins root " + pluginsRoot,
                    exception);
            }
            return artifactIdsInternal();
        });
    }

    public String loadArtifact(Path pluginPath) {
        Objects.requireNonNull(pluginPath, "pluginPath");
        return runExclusive(() -> loadArtifactInternal(normalize(pluginPath)));
    }

    public String reloadArtifact(Path candidatePath) {
        Objects.requireNonNull(candidatePath, "candidatePath");
        return runExclusive(() -> reloadArtifactInternal(normalize(candidatePath)));
    }

    public Class<?> configType(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        return runExclusive(() -> configTypeInternal(pluginId));
    }

    public Fibra mount(PluginInstanceSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return runExclusive(() -> mountInternal(spec));
    }

    public Fibra update(String entryId, Object config) {
        Objects.requireNonNull(entryId, "entryId");
        return runExclusive(() -> updateInternal(entryId, ignored -> config, true));
    }

    public Fibra updateWithFactory(String entryId, PluginConfigFactory configFactory) {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(configFactory, "configFactory");
        return runExclusive(() -> updateInternal(entryId, configFactory, false));
    }

    public void unmount(String entryId) {
        Objects.requireNonNull(entryId, "entryId");
        runExclusive(() -> unmountInternal(entryId));
    }

    public void stopArtifact(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        runExclusive(() -> stopArtifactInternal(pluginId));
    }

    public boolean unloadArtifact(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        return runExclusive(() -> unloadArtifactInternal(pluginId));
    }

    public List<String> artifactIds() {
        return runExclusive(this::artifactIdsInternal);
    }

    public List<String> entryIds() {
        return runExclusive(() -> List.copyOf(entries.keySet()));
    }

    public Optional<Fibra> fibra(String entryId) {
        Objects.requireNonNull(entryId, "entryId");
        return runExclusive(() -> Optional.ofNullable(entries.get(entryId))
            .map(MountedEntry::fibra));
    }

    /** 在制品更新和配置 reconcile 共用的可重入串行边界内执行。 */
    public <T> T runExclusive(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        lifecycleLock.lock();
        try {
            requireOpen();
            return action.get();
        } finally {
            lifecycleLock.unlock();
        }
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
        lifecycleLock.lock();
        try {
            if (closed) {
                return;
            }
            var mounted = new ArrayList<>(entries.keySet());
            Collections.reverse(mounted);
            for (var entryId : mounted) {
                unmountInternal(entryId);
            }
            pluginManager.stopPlugins();
            pluginManager.unloadPlugins();
            closed = true;
        } finally {
            lifecycleLock.unlock();
        }
    }

    FibraPluginClassLoader pluginClassLoader(String pluginId) {
        return (FibraPluginClassLoader) pluginManager.getPluginClassLoader(pluginId);
    }

    ClassLoader entrypointClassLoader(String entryId) {
        var entry = entries.get(entryId);
        return entry == null ? null : entry.entrypointClassLoader();
    }

    FibraPluginCandidate inspectCandidate(Path candidatePath) {
        return validateArtifact(normalize(candidatePath));
    }

    String currentPluginVersion(String pluginId) {
        return runExclusive(() -> {
            var plugin = pluginManager.getPlugin(pluginId);
            return plugin == null ? null : plugin.getDescriptor().getVersion();
        });
    }

    private String loadArtifactInternal(Path pluginPath) {
        requireDirectChild(pluginPath);
        validateArtifact(pluginPath);
        return pluginManager.loadPluginsStrict(List.of(pluginPath)).getFirst();
    }

    private String reloadArtifactInternal(Path candidatePath) {
        if (!Files.isRegularFile(candidatePath)) {
            throw new IllegalArgumentException("candidate must be a regular JAR: "
                + candidatePath);
        }
        var candidate = validateArtifact(candidatePath);
        var current = pluginManager.getPlugin(candidate.pluginId());
        if (current == null) {
            throw new IllegalArgumentException("cannot reload unknown plugin "
                + candidate.pluginId());
        }

        var currentPath = normalize(current.getPluginPath());
        if (candidatePath.equals(currentPath)) {
            throw new IllegalArgumentException(
                "candidate must not overwrite the currently loaded plugin JAR");
        }
        if (Objects.equals(candidatePath.getParent(), pluginsRoot)) {
            throw new IllegalArgumentException(
                "candidate JAR must be staged outside the plugins root");
        }

        var affectedIds = dependentFirst(candidate.pluginId());
        var artifactPaths = new ArrayList<Path>(affectedIds.size());
        for (var pluginId : affectedIds) {
            artifactPaths.add(normalize(pluginManager.getPlugin(pluginId).getPluginPath()));
        }
        var startedIds = startedArtifactIds();
        startedIds.retainAll(new LinkedHashSet<>(affectedIds));
        var instanceSpecs = entries.values().stream()
            .map(MountedEntry::spec)
            .filter(spec -> affectedIds.contains(spec.pluginId()))
            .toList();

        Path staged = null;
        Path backup = null;
        var oldArtifactMoved = false;
        var runtimeMutationStarted = false;
        try {
            staged = Files.createTempFile(pluginsRoot, ".fibra-stage-", ".tmp");
            Files.copy(candidatePath, staged, StandardCopyOption.REPLACE_EXISTING);
            validateArtifact(staged);
            backup = Files.createTempFile(pluginsRoot, ".fibra-backup-", ".tmp");

            runtimeMutationStarted = true;
            unloadAffected(affectedIds);
            moveAtomically(currentPath, backup);
            oldArtifactMoved = true;
            moveAtomically(staged, currentPath);
            staged = null;

            loadArtifactPaths(artifactPaths);
            restoreRuntime(startedIds, instanceSpecs);
            deleteTemporary(backup);
            backup = null;
            return candidate.pluginId();
        } catch (RuntimeException | IOException failure) {
            if (runtimeMutationStarted) {
                recoverReload(candidate.pluginId(), affectedIds, artifactPaths, startedIds,
                    instanceSpecs, currentPath, backup, oldArtifactMoved, failure);
            }
            throw new IllegalStateException("failed to reload plugin " + candidate.pluginId(),
                failure);
        } finally {
            deleteTemporary(staged);
            if (oldArtifactMoved && backup != null && !Files.notExists(backup)) {
                LOGGER.error("Preserving Fibra plugin backup after failed recovery: {}", backup);
            } else {
                deleteTemporary(backup);
            }
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
        for (var affectedId : dependentFirst(pluginId)) {
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
        stopArtifactInternal(pluginId);
        return pluginManager.unloadPlugin(pluginId);
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
        if (pluginManager.getPlugin(pluginId) == null) {
            throw new IllegalArgumentException("unknown plugin " + pluginId);
        }
        startArtifactInternal(pluginId);
        var found = pluginManager.getExtensionClasses(FibraPluginEntrypoint.class, pluginId);
        if (found.size() != 1) {
            throw new IllegalStateException("plugin " + pluginId
                + " must provide exactly one FibraPluginEntrypoint, found " + found.size());
        }
        try {
            return (FibraPluginEntrypoint<?>) found.getFirst().getDeclaredConstructor()
                .newInstance();
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException
                 | InvocationTargetException exception) {
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
        return entries.values().stream().anyMatch(entry -> entry.spec().pluginId().equals(pluginId));
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

    private List<String> dependentFirst(String pluginId) {
        if (pluginManager.getPlugin(pluginId) == null) {
            throw new IllegalArgumentException("unknown plugin " + pluginId);
        }
        var result = new ArrayList<String>();
        collectDependents(pluginId, new LinkedHashSet<>(), result);
        return result;
    }

    private void collectDependents(String pluginId, Set<String> visited, List<String> result) {
        if (!visited.add(pluginId)) {
            return;
        }
        for (var plugin : pluginManager.getPlugins()) {
            var dependsOnPlugin = plugin.getDescriptor().getDependencies().stream()
                .anyMatch(dependency -> dependency.getPluginId().equals(pluginId));
            if (dependsOnPlugin) {
                collectDependents(plugin.getPluginId(), visited, result);
            }
        }
        result.add(pluginId);
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

    private FibraPluginCandidate validateArtifact(Path pluginPath) {
        try (var jar = new JarFile(pluginPath.toFile())) {
            var manifest = jar.getManifest();
            if (manifest == null) {
                throw new IllegalArgumentException("plugin JAR has no manifest: " + pluginPath);
            }
            var attributes = manifest.getMainAttributes();
            var pluginId = attributes.getValue("Plugin-Id");
            if (pluginId == null || pluginId.isBlank()) {
                throw new IllegalArgumentException("plugin JAR has no Plugin-Id: " + pluginPath);
            }
            var version = attributes.getValue("Plugin-Version");
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException(
                    "plugin JAR has no Plugin-Version: " + pluginPath);
            }
            try {
                VERSIONS.compareVersions(version, version);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("invalid Plugin-Version " + version
                    + ": " + pluginPath, exception);
            }
            var pluginClass = attributes.getValue("Plugin-Class");
            if (pluginClass != null && !pluginClass.isBlank()) {
                throw new IllegalArgumentException(
                    "Fibra plugin must not declare Plugin-Class: " + pluginPath);
            }
            var bundled = jar.stream()
                .map(entry -> entry.getName())
                .filter(name -> name.endsWith(".class"))
                .filter(FibraPluginLoader::isSharedClass)
                .findFirst();
            if (bundled.isPresent()) {
                throw new IllegalArgumentException("plugin must not bundle shared class "
                    + bundled.get() + ": " + pluginPath);
            }
            return new FibraPluginCandidate(pluginId, version,
                Files.getLastModifiedTime(pluginPath));
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot inspect plugin JAR " + pluginPath,
                exception);
        }
    }

    private void unloadAffected(List<String> affectedIds) {
        for (var pluginId : affectedIds) {
            unmountEntriesForArtifact(pluginId);
            var plugin = pluginManager.getPlugin(pluginId);
            if (plugin != null && plugin.getPluginState() == PluginState.STARTED) {
                pluginManager.stopPlugin(pluginId);
            }
        }
        pluginManager.unloadPluginsStrict(affectedIds);
    }

    private void loadArtifactPaths(List<Path> artifactPaths) {
        var sorted = artifactPaths.stream().sorted().toList();
        for (var path : sorted) {
            requireDirectChild(path);
            validateArtifact(path);
        }
        pluginManager.loadPluginsStrict(sorted);
    }

    private void restoreRuntime(Set<String> startedIds, List<PluginInstanceSpec> specs) {
        var resolved = pluginManager.getResolvedPlugins().stream()
            .map(PluginWrapper::getPluginId)
            .filter(startedIds::contains)
            .toList();
        for (var pluginId : resolved) {
            startArtifactInternal(pluginId);
        }
        for (var spec : specs) {
            mountInternal(spec);
        }
    }

    private void recoverReload(String pluginId, List<String> affectedIds,
                               List<Path> artifactPaths, Set<String> startedIds,
                               List<PluginInstanceSpec> specs, Path currentPath,
                               Path backup, boolean oldArtifactMoved, Throwable failure) {
        try {
            unloadAffected(affectedIds);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        if (oldArtifactMoved && backup != null && Files.exists(backup)) {
            try {
                moveAtomically(backup, currentPath);
            } catch (IOException restoreFailure) {
                failure.addSuppressed(restoreFailure);
                return;
            }
        }
        try {
            loadArtifactPaths(artifactPaths);
            restoreRuntime(startedIds, specs);
        } catch (RuntimeException restoreFailure) {
            failure.addSuppressed(new IllegalStateException(
                "failed to restore plugin " + pluginId, restoreFailure));
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteTemporary(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            LOGGER.warn("Cannot delete Fibra plugin temporary file: {}", path, exception);
        }
    }

    private void requireDirectChild(Path pluginPath) {
        if (!Files.isRegularFile(pluginPath)
            || !pluginPath.getFileName().toString().endsWith(".jar")
            || !Objects.equals(pluginPath.getParent(), pluginsRoot)) {
            throw new IllegalArgumentException(
                "plugin must be a direct JAR child of " + pluginsRoot + ": " + pluginPath);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("FibraPluginLoader is closed");
        }
    }

    private static Path validatePluginsRoot(Path path) {
        Objects.requireNonNull(path, "pluginsRoot");
        var normalized = normalize(path);
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("pluginsRoot must be an existing directory: "
                + normalized);
        }
        return normalized;
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static boolean isSharedClass(String entryName) {
        var classPath = entryName;
        var versionsPrefix = "META-INF/versions/";
        if (classPath.startsWith(versionsPrefix)) {
            var versionEnd = classPath.indexOf('/', versionsPrefix.length());
            if (versionEnd >= 0) {
                classPath = classPath.substring(versionEnd + 1);
            }
        }
        var candidate = classPath;
        return FORBIDDEN_ARTIFACT_PREFIXES.stream().anyMatch(candidate::startsWith);
    }

    private record MountedEntry(PluginInstanceSpec spec, Fibra fibra,
                                ClassLoader entrypointClassLoader) {
    }
}
