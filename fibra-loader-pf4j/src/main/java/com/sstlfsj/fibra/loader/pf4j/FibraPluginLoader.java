package com.sstlfsj.fibra.loader.pf4j;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Fibra;
import com.sstlfsj.fibra.PluginDescriptor;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.jar.JarFile;

/** 把 PF4J JAR 制品生命周期桥接到宿主 Fibra Context。 */
public final class FibraPluginLoader implements AutoCloseable {
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
    private final Map<String, Fibra> fibras = new LinkedHashMap<>();
    private final Map<String, ClassLoader> entrypointClassLoaders = new LinkedHashMap<>();
    private boolean closed;

    public FibraPluginLoader(Context root, Path pluginsRoot) {
        this.root = Objects.requireNonNull(root, "root");
        if (root.root() != root) {
            throw new IllegalArgumentException("root must be the Fibra root Context");
        }
        this.pluginsRoot = validatePluginsRoot(pluginsRoot);
        this.pluginManager = new FibraJarPluginManager(this.pluginsRoot);
    }

    public List<String> loadPlugins() {
        return locked(() -> {
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
            return pluginIdsInternal();
        });
    }

    public String loadPlugin(Path pluginPath) {
        Objects.requireNonNull(pluginPath, "pluginPath");
        return locked(() -> loadPluginInternal(normalize(pluginPath)));
    }

    public Fibra startPlugin(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        return locked(() -> startPluginInternal(pluginId));
    }

    public void startPlugins() {
        lockedRun(() -> {
            var ids = pluginManager.getResolvedPlugins().stream()
                .map(PluginWrapper::getPluginId)
                .toList();
            for (var pluginId : ids) {
                startPluginInternal(pluginId);
            }
        });
    }

    public void stopPlugin(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        lockedRun(() -> stopPluginInternal(pluginId));
    }

    public boolean unloadPlugin(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        return locked(() -> {
            if (pluginManager.getPlugin(pluginId) == null) {
                return false;
            }
            stopPluginInternal(pluginId);
            return pluginManager.unloadPlugin(pluginId);
        });
    }

    public List<String> pluginIds() {
        return locked(this::pluginIdsInternal);
    }

    public Optional<Fibra> fibra(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        return locked(() -> Optional.ofNullable(fibras.get(pluginId)));
    }

    @Override
    public void close() {
        lifecycleLock.lock();
        try {
            if (closed) {
                return;
            }
            var started = new ArrayList<>(fibras.keySet());
            Collections.reverse(started);
            for (var pluginId : started) {
                disposeFibra(pluginId);
            }
            pluginManager.stopPlugins();
            pluginManager.unloadPlugins();
            entrypointClassLoaders.clear();
            closed = true;
        } finally {
            lifecycleLock.unlock();
        }
    }

    FibraPluginClassLoader pluginClassLoader(String pluginId) {
        return (FibraPluginClassLoader) pluginManager.getPluginClassLoader(pluginId);
    }

    ClassLoader entrypointClassLoader(String pluginId) {
        return entrypointClassLoaders.get(pluginId);
    }

    private String loadPluginInternal(Path pluginPath) {
        requireDirectChild(pluginPath);
        validateArtifact(pluginPath);
        return pluginManager.loadPluginsStrict(List.of(pluginPath)).getFirst();
    }

    private Fibra startPluginInternal(String pluginId) {
        var existing = fibras.get(pluginId);
        if (existing != null) {
            return existing;
        }

        var startedBefore = startedPluginIds();
        var state = pluginManager.startPlugin(pluginId);
        if (state != PluginState.STARTED) {
            throw new IllegalStateException("PF4J plugin did not start: " + pluginId
                + " (state=" + state + ")");
        }

        var newlyStarted = pluginManager.getStartedPlugins().stream()
            .map(PluginWrapper::getPluginId)
            .filter(id -> !startedBefore.contains(id))
            .toList();
        try {
            for (var id : newlyStarted) {
                startFibra(id);
            }
            if (!fibras.containsKey(pluginId)) {
                startFibra(pluginId);
            }
            return fibras.get(pluginId);
        } catch (RuntimeException exception) {
            var rollback = new ArrayList<>(newlyStarted);
            Collections.reverse(rollback);
            for (var id : rollback) {
                disposeFibra(id);
                if (pluginManager.getPlugin(id).getPluginState() == PluginState.STARTED) {
                    pluginManager.stopPlugin(id);
                }
            }
            throw exception;
        }
    }

    private void startFibra(String pluginId) {
        if (fibras.containsKey(pluginId)) {
            return;
        }
        var entrypoints = pluginManager.getExtensions(FibraPluginEntrypoint.class, pluginId);
        if (entrypoints.size() != 1) {
            throw new IllegalStateException("plugin " + pluginId
                + " must provide exactly one FibraPluginEntrypoint, found "
                + entrypoints.size());
        }
        var entrypoint = entrypoints.getFirst();
        var fibra = root.plugin(PluginDescriptor.<Void>builder(pluginId).build(), entrypoint, null);
        try {
            fibra.ready().block();
            fibras.put(pluginId, fibra);
            entrypointClassLoaders.put(pluginId, entrypoint.getClass().getClassLoader());
        } catch (RuntimeException exception) {
            fibra.dispose().block();
            throw exception;
        }
    }

    private void stopPluginInternal(String pluginId) {
        for (var id : dependentFirst(pluginId)) {
            disposeFibra(id);
            var plugin = pluginManager.getPlugin(id);
            if (plugin.getPluginState() == PluginState.STARTED) {
                pluginManager.stopPlugin(id);
            }
        }
    }

    private void disposeFibra(String pluginId) {
        var fibra = fibras.remove(pluginId);
        entrypointClassLoaders.remove(pluginId);
        if (fibra != null) {
            fibra.dispose().block();
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

    private Set<String> startedPluginIds() {
        var result = new LinkedHashSet<String>();
        for (var plugin : pluginManager.getStartedPlugins()) {
            result.add(plugin.getPluginId());
        }
        return result;
    }

    private List<String> pluginIdsInternal() {
        return pluginManager.getPlugins().stream()
            .map(PluginWrapper::getPluginId)
            .sorted()
            .toList();
    }

    private void validateArtifact(Path pluginPath) {
        try (var jar = new JarFile(pluginPath.toFile())) {
            var manifest = jar.getManifest();
            if (manifest == null) {
                throw new IllegalArgumentException("plugin JAR has no manifest: " + pluginPath);
            }
            var pluginClass = manifest.getMainAttributes().getValue("Plugin-Class");
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
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot inspect plugin JAR " + pluginPath,
                exception);
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

    private <T> T locked(java.util.function.Supplier<T> action) {
        lifecycleLock.lock();
        try {
            requireOpen();
            return action.get();
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void lockedRun(Runnable action) {
        locked(() -> {
            action.run();
            return null;
        });
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
}
