package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.PluginFacet;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/** P0 payload 为单 JAR；不发现 payload 外的共享 lib 或隐式 Class-Path。 */
final class JavaFacetDescriptorReader {
    static final String LOCATION = "META-INF/fibra/plugin.yaml";
    private static final int MAX_BYTES = 64 * 1024;
    private final YAMLMapper yaml = YAMLMapper.builder(YAMLFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).build();

    JavaFacetDescriptor read(PluginFacet facet) {
        if (!JavaRuntimeProvider.RUNTIME_ID.equals(facet.runtimeId())) {
            throw new IllegalArgumentException("facet runtime is not Java");
        }
        var payload = facet.payload();
        if (!Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS)
            || !payload.getFileName().toString().endsWith(".jar")) {
            throw error("Java facet payload must be a regular JAR", null);
        }
        try (var jar = new JarFile(payload.toFile(), true)) {
            var manifest = jar.getManifest();
            var classPath = manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (classPath != null && !classPath.isBlank()) {
                throw error("Java facet JAR must not declare Class-Path", null);
            }
            var entry = jar.getJarEntry(LOCATION);
            if (entry == null || entry.isDirectory()) throw error("missing " + LOCATION, null);
            final Object raw;
            try (var input = jar.getInputStream(entry)) {
                var bytes = input.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw error("Java facet descriptor is too large", null);
                raw = yaml.readValue(bytes, Object.class);
            }
            if (!(raw instanceof Map<?, ?> values)) throw error("Java facet descriptor must be an object", null);
            if (values.keySet().stream().anyMatch(key -> !"entrypoint".equals(key))) {
                throw error("unknown Java facet descriptor fields " + values.keySet(), null);
            }
            if (!values.containsKey("entrypoint")) return new JavaFacetDescriptor(Optional.empty());
            if (!(values.get("entrypoint") instanceof String name) || name.isBlank()) {
                throw error("entrypoint must be a non-blank string", null);
            }
            return new JavaFacetDescriptor(Optional.of(name));
        } catch (JavaRuntimeException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw error("cannot read Java facet descriptor", failure);
        }
    }

    private static JavaRuntimeException error(String message, Throwable cause) {
        return new JavaRuntimeException(JavaRuntimePhase.MANIFEST, null, message, cause);
    }
}
