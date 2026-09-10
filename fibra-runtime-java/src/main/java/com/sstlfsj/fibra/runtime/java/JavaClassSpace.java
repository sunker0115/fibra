package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.engine.PluginCatalog;
import com.sstlfsj.fibra.engine.PluginCatalogEntry;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JavaClassSpace implements AutoCloseable {
    private final JavaArtifactGraph graph;
    private final Map<ArtifactId, PluginClassLoader> loaders;
    private final PluginCatalog catalog;
    private boolean closed;

    private JavaClassSpace(JavaArtifactGraph graph,
                           Map<ArtifactId, PluginClassLoader> loaders,
                           PluginCatalog catalog) {
        this.graph = graph;
        this.loaders = Map.copyOf(loaders);
        this.catalog = catalog;
    }

    static JavaClassSpace open(List<ArtifactRecord> artifacts,
                               Map<ArtifactId, JavaPluginManifest> manifests,
                               ClassLoader parent, List<String> parentPackages) {
        var graph = JavaArtifactGraph.resolve(manifests.values());
        var records = new LinkedHashMap<ArtifactId, ArtifactRecord>();
        artifacts.forEach(record -> records.put(record.id(), record));
        var loaders = new LinkedHashMap<ArtifactId, PluginClassLoader>();
        try {
            for (var id : graph.dependencyFirst()) {
                var record = records.get(id);
                loaders.put(id, new PluginClassLoader(record.location().toUri().toURL(),
                    parent, parentPackages));
            }
            for (var id : graph.dependencyFirst()) {
                var dependencies = graph.manifest(id).requires().stream()
                    .map(requirement -> loaders.get(requirement.artifactId())).toList();
                loaders.get(id).dependencies(dependencies);
            }
            var entries = new ArrayList<PluginCatalogEntry<?>>();
            for (var id : graph.dependencyFirst()) {
                var manifest = graph.manifest(id);
                if (manifest.entrypoint().isEmpty()) {
                    continue;
                }
                var type = Class.forName(manifest.entrypoint().orElseThrow(), true,
                    loaders.get(id));
                var entrypoint = type.getDeclaredConstructor().newInstance();
                if (!(entrypoint instanceof PluginEntrypoint<?> pluginEntrypoint)) {
                    throw error(id, "entrypoint does not implement PluginEntrypoint", null);
                }
                entries.add(entry(pluginEntrypoint));
            }
            return new JavaClassSpace(graph, loaders,
                PluginCatalog.of(entries.toArray(PluginCatalogEntry[]::new)));
        } catch (JavaRuntimeException exception) {
            close(loaders, graph.dependencyFirst());
            throw exception;
        } catch (ReflectiveOperationException | IOException | LinkageError exception) {
            close(loaders, graph.dependencyFirst());
            throw error(null, "cannot load Java plugin class space", exception);
        }
    }

    PluginCatalog catalog() {
        return catalog;
    }

    Map<ArtifactId, PluginClassLoader> loaders() {
        return loaders;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PluginCatalogEntry<?> entry(PluginEntrypoint<?> entrypoint) {
        var definition = entrypoint.definition();
        var mapper = new YAMLMapper();
        return new PluginCatalogEntry(definition, value -> {
            if (value == null || definition.configType().isInstance(value)) {
                return value;
            }
            return mapper.convertValue(value, definition.configType());
        });
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        close(loaders, graph.dependencyFirst());
    }

    private static void close(Map<ArtifactId, PluginClassLoader> loaders,
                              List<ArtifactId> order) {
        var reverse = new ArrayList<>(order);
        Collections.reverse(reverse);
        IOException failure = null;
        for (var id : reverse) {
            var loader = loaders.get(id);
            if (loader == null) {
                continue;
            }
            try {
                loader.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw error(JavaRuntimePhase.CLOSE, null,
                "cannot close Java plugin class space", failure);
        }
    }

    private static JavaRuntimeException error(ArtifactId id, String message,
                                              Throwable cause) {
        return error(JavaRuntimePhase.LOAD, id, message, cause);
    }

    private static JavaRuntimeException error(JavaRuntimePhase phase, ArtifactId id,
                                              String message, Throwable cause) {
        return new JavaRuntimeException(phase, id, message, cause);
    }
}
