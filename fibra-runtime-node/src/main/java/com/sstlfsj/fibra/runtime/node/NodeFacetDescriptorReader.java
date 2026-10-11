package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ManagedFacet;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** 只读取受管 Node facet payload 内的局部描述，不读取逻辑 package 身份或版本。 */
final class NodeFacetDescriptorReader {
    static final String FILE_NAME = "fibra-plugin.yaml";
    private static final Set<String> FIELDS = Set.of(
        "protocol", "definitionId", "entrypoint", "contributions");
    private static final Set<String> ENDPOINT_FIELDS = Set.of(
        "name", "kind", "schemaVersion", "method", "descriptor");

    private final YAMLMapper yaml = YAMLMapper.builder(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .build();

    NodeFacetDescriptor read(ManagedFacet managed, Resources resources) {
        var facet = managed.facet();
        var owner = managed.artifactId();
        var payload = facet.payload();
        var descriptor = payload.resolve(FILE_NAME);
        try {
            validatePayload(payload, owner, resources);
            if (Files.isSymbolicLink(descriptor)
                || !Files.isRegularFile(descriptor, LinkOption.NOFOLLOW_LINKS)
                || Files.size(descriptor) > 64 * 1024) {
                throw error(owner, "missing or invalid " + FILE_NAME, null);
            }
            var bytes = resources.open(descriptor).readNBytes(64 * 1024 + 1);
            if (bytes.length > 64 * 1024) throw error(owner, "missing or invalid " + FILE_NAME, null);
            var raw = yaml.readValue(bytes, Object.class);
            var values = map(raw, owner, "Node facet descriptor");
            rejectUnknown(values, FIELDS, owner, "descriptor");
            var protocol = positiveInteger(values.get("protocol"), "protocol", owner);
            var definitionId = text(values.get("definitionId"), "definitionId", owner);
            var entrypoint = text(values.get("entrypoint"), "entrypoint", owner);
            validateEntrypoint(payload, owner, entrypoint);
            return new NodeFacetDescriptor(protocol, definitionId, entrypoint,
                contributions(values.get("contributions"), owner));
        } catch (NodeRuntimeException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw error(owner, "cannot read Node facet descriptor", failure);
        }
    }

    private static void validatePayload(Path payload, ArtifactId owner, Resources resources) throws IOException {
        if (!Files.isDirectory(payload, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(payload)) {
            throw error(owner, "Node facet payload must be a directory", null);
        }
        var root = payload.toRealPath();
        if (resources.walk(root).anyMatch(Files::isSymbolicLink)) {
            throw error(owner, "Node facet payload must not contain symbolic links", null);
        }
    }

    /** Each independent file handle is registered before traversal or parsing. */
    static final class Resources {
        private final ResourceAccess access;
        private final ArtifactId owner;
        private Stream<Path> paths;
        private InputStream input;

        Resources(ResourceAccess access, ArtifactId owner) { this.access = access; this.owner = owner; }

        Stream<Path> walk(Path root) throws IOException {
            paths = access.walk(root);
            return paths;
        }

        InputStream open(Path descriptor) throws IOException {
            input = access.open(descriptor);
            return input;
        }

        void close() {
            NodeRuntimeException failure = null;
            if (input != null) {
                try { access.close(input); input = null; }
                catch (Throwable error) { failure = append(failure, error); }
            }
            // The directory walk and descriptor stream have no enclosing-handle dependency.
            if (paths != null) {
                try { access.close(paths); paths = null; }
                catch (Throwable error) {
                    failure = append(failure, error);
                }
            }
            if (failure != null) throw failure;
        }

        private NodeRuntimeException append(NodeRuntimeException previous, Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            var normalized = new NodeRuntimeException(owner,
                "cannot close Node descriptor resource: " + failure.getClass().getName(), null);
            if (previous == null) return normalized;
            previous.addSuppressed(normalized);
            return previous;
        }
    }

    /** Narrow file seam for resource lifecycle tests, never public configuration. */
    static class ResourceAccess {
        Stream<Path> walk(Path root) throws IOException { return Files.walk(root); }
        InputStream open(Path descriptor) throws IOException { return Files.newInputStream(descriptor); }
        void close(InputStream input) throws IOException { input.close(); }
        void close(Stream<Path> paths) { paths.close(); }
    }

    private static void validateEntrypoint(Path payload, ArtifactId owner,
                                           String entrypoint) throws IOException {
        var root = payload.toRealPath();
        var relative = Path.of(entrypoint);
        if (relative.isAbsolute()) {
            throw error(owner, "entrypoint must be relative to payload", null);
        }
        var candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root) || Files.isSymbolicLink(candidate)
            || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw error(owner,
                "entrypoint must be a JavaScript file inside payload", null);
        }
        var lower = candidate.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!(lower.endsWith(".js") || lower.endsWith(".mjs")
            || lower.endsWith(".cjs"))) {
            throw error(owner,
                "entrypoint must be a JavaScript file inside payload", null);
        }
    }

    private static List<NodeEndpointManifest> contributions(Object raw,
                                                              ArtifactId owner) {
        if (!(raw instanceof List<?> entries)) {
            throw error(owner, "contributions must be an array", null);
        }
        var result = new ArrayList<NodeEndpointManifest>();
        var names = new HashSet<String>();
        for (var entry : entries) {
            var values = map(entry, owner, "contribution");
            rejectUnknown(values, ENDPOINT_FIELDS, owner, "contribution");
            var endpoint = new NodeEndpointManifest(
                text(values.get("name"), "contributions.name", owner),
                text(values.get("kind"), "contributions.kind", owner),
                positiveInteger(values.get("schemaVersion"),
                    "contributions.schemaVersion", owner),
                text(values.get("method"), "contributions.method", owner),
                values.get("descriptor"));
            if (!names.add(endpoint.name())) {
                throw error(owner, "duplicate contribution " + endpoint.name(), null);
            }
            result.add(endpoint);
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> map(Object raw, ArtifactId owner, String name) {
        if (!(raw instanceof Map<?, ?> source)) {
            throw error(owner, name + " must be an object", null);
        }
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw error(owner, name + " keys must be strings", null);
            }
            result.put(text, value);
        });
        return result;
    }

    private static void rejectUnknown(Map<String, Object> values, Set<String> allowed,
                                      ArtifactId owner, String name) {
        var unknown = values.keySet().stream().filter(key -> !allowed.contains(key)).toList();
        if (!unknown.isEmpty()) {
            throw error(owner, "unknown " + name + " fields " + unknown, null);
        }
        var missing = allowed.stream().filter(key -> !values.containsKey(key)).toList();
        if (!missing.isEmpty()) {
            throw error(owner, "missing " + name + " fields " + missing, null);
        }
    }

    private static String text(Object value, String field, ArtifactId owner) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw error(owner, field + " must be a non-blank string", null);
        }
        return text;
    }

    private static int positiveInteger(Object value, String field, ArtifactId owner) {
        if (!(value instanceof Number number) || number.intValue() <= 0
            || number.doubleValue() != number.intValue()) {
            throw error(owner, field + " must be a positive integer", null);
        }
        return number.intValue();
    }

    private static NodeRuntimeException error(ArtifactId owner, String message,
                                              Throwable cause) {
        return new NodeRuntimeException(owner, message, cause);
    }
}
