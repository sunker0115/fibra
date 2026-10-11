package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.PluginFacet;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.util.jar.Manifest;

/** P0 payload 为单 JAR；不发现 payload 外的共享 lib 或隐式 Class-Path。 */
final class JavaFacetDescriptorReader {
    static final String LOCATION = "META-INF/fibra/plugin.yaml";
    private static final int MAX_BYTES = 64 * 1024;
    private final YAMLMapper yaml = YAMLMapper.builder(YAMLFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).build();

    JavaFacetDescriptor read(PluginFacet facet, Resources resources) {
        if (!JavaRuntimeProvider.RUNTIME_ID.equals(facet.runtimeId())) {
            throw new IllegalArgumentException("facet runtime is not Java");
        }
        var payload = facet.payload();
        if (!Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS)
            || !payload.getFileName().toString().endsWith(".jar")) {
            throw error("Java facet payload must be a regular JAR", null);
        }
        try {
            var jar = resources.open(payload);
            var manifestEntry = jar.getJarEntry(JarFile.MANIFEST_NAME);
            if (manifestEntry == null) {
                // JarFile's manifest lookup also accepts a case-insensitive entry name.
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var candidate = entries.nextElement();
                    if (JarFile.MANIFEST_NAME.equalsIgnoreCase(candidate.getName())) {
                        manifestEntry = candidate;
                        break;
                    }
                }
            }
            var manifest = manifestEntry == null ? null
                : new Manifest(resources.open(jar, manifestEntry));
            var classPath = manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (classPath != null && !classPath.isBlank()) {
                throw error("Java facet JAR must not declare Class-Path", null);
            }
            var entry = jar.getJarEntry(LOCATION);
            if (entry == null || entry.isDirectory()) throw error("missing " + LOCATION, null);
            var input = resources.open(jar, entry);
            var bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw error("Java facet descriptor is too large", null);
            var raw = yaml.readValue(bytes, Object.class);
            if (!(raw instanceof Map<?, ?> values)) throw error("Java facet descriptor must be an object", null);
            if (values.keySet().stream().anyMatch(key -> !"entrypoint".equals(key) && !"definitionId".equals(key))) {
                throw error("unknown Java facet descriptor fields " + values.keySet(), null);
            }
            if (values.isEmpty()) return new JavaFacetDescriptor(Optional.empty(), Optional.empty());
            if (!(values.get("entrypoint") instanceof String name) || name.isBlank()) {
                throw error("entrypoint must be a non-blank string", null);
            }
            if (!(values.get("definitionId") instanceof String definitionId) || definitionId.isBlank()) {
                throw error("definitionId must be a non-blank string", null);
            }
            return new JavaFacetDescriptor(Optional.of(name), Optional.of(definitionId));
        } catch (JavaRuntimeException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw error("cannot read Java facet descriptor", failure);
        }
    }

    /** File handles are registered here immediately after opening, before any parsing. */
    static final class Resources {
        private final ResourceAccess access;
        private JarFile jar;
        private final ArrayList<InputStream> streams = new ArrayList<>(2);

        Resources(ResourceAccess access) { this.access = access; }

        JarFile open(Path path) throws IOException {
            jar = access.open(path);
            return jar;
        }

        InputStream open(JarFile jar, JarEntry entry) throws IOException {
            var stream = access.open(jar, entry);
            streams.add(stream);
            return stream;
        }

        void close() {
            JavaRuntimeException failure = null;
            for (int index = streams.size() - 1; index >= 0; index--) {
                try {
                    access.close(streams.get(index));
                    streams.remove(index);
                } catch (Throwable error) {
                    failure = append(failure, error);
                }
            }
            // A retained inner handle requires its enclosing JAR to remain open.
            if (streams.isEmpty() && jar != null) {
                try {
                    access.close(jar);
                    jar = null;
                } catch (Throwable error) {
                    failure = append(failure, error);
                }
            }
            if (failure != null) throw failure;
        }

        private static JavaRuntimeException append(JavaRuntimeException previous, Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            var normalized = new JavaRuntimeException(JavaRuntimePhase.CLOSE, null,
                "cannot close Java descriptor resource: " + failure.getClass().getName(), null);
            if (previous == null) return normalized;
            previous.addSuppressed(normalized);
            return previous;
        }
    }

    /** Narrow package-private file seam; never a public runtime configuration. */
    static class ResourceAccess {
        JarFile open(Path path) throws IOException { return new JarFile(path.toFile(), true); }
        InputStream open(JarFile jar, JarEntry entry) throws IOException { return jar.getInputStream(entry); }
        void close(InputStream stream) throws IOException { stream.close(); }
        void close(JarFile jar) throws IOException { jar.close(); }
    }

    private static JavaRuntimeException error(String message, Throwable cause) {
        return new JavaRuntimeException(JavaRuntimePhase.MANIFEST, null, message, cause);
    }
}
