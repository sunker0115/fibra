package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactPackage;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class NodeManifestReader {
    static final String FILE_NAME = "fibra-plugin.yaml";
    private static final Set<String> FIELDS = Set.of(
        "id", "version", "protocol", "entrypoint", "contributions");
    private static final Set<String> ENDPOINT_FIELDS = Set.of(
        "name", "kind", "schemaVersion", "method", "descriptor");

    private final YAMLMapper yaml = YAMLMapper.builder(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .build();

    NodePluginManifest read(ArtifactPackage artifact, ArtifactId expectedId,
                            String expectedVersion) {
        var payload = artifact.payload();
        var manifest = payload.resolve(FILE_NAME);
        try {
            if (!Files.isDirectory(payload) || Files.isSymbolicLink(manifest)
                || !Files.isRegularFile(manifest) || Files.size(manifest) > 64 * 1024) {
                throw error(expectedId, "missing or invalid " + FILE_NAME, null);
            }
            var raw = yaml.readValue(manifest.toFile(), Object.class);
            if (!(raw instanceof Map<?, ?> map)) {
                throw error(expectedId, "Node plugin manifest must be an object", null);
            }
            var values = stringMap(map, expectedId);
            rejectUnknown(values, FIELDS, expectedId);
            var id = new ArtifactId(text(values.get("id"), "id", expectedId));
            var version = text(values.get("version"), "version", expectedId);
            if (expectedId != null && !id.equals(expectedId)) {
                throw error(expectedId, "manifest id does not match artifact id", null);
            }
            if (expectedVersion != null && !version.equals(expectedVersion)) {
                throw error(expectedId, "manifest version does not match artifact version", null);
            }
            var protocol = positiveInteger(values.get("protocol"), "protocol", expectedId);
            var entrypoint = text(values.get("entrypoint"), "entrypoint", expectedId);
            validateEntrypoint(payload, expectedId, entrypoint);
            return new NodePluginManifest(id, version, protocol, entrypoint,
                contributions(values.get("contributions"), expectedId));
        } catch (NodeRuntimeException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw error(expectedId, "cannot read Node plugin manifest", failure);
        }
    }

    private static void validateEntrypoint(java.nio.file.Path payload, ArtifactId owner,
                                           String entrypoint)
        throws IOException {
        var root = payload.toRealPath();
        var relative = java.nio.file.Path.of(entrypoint);
        if (relative.isAbsolute()) {
            throw error(owner, "entrypoint must be relative to payload", null);
        }
        var path = root.resolve(relative).normalize().toRealPath();
        var lower = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!path.startsWith(root) || Files.isSymbolicLink(root.resolve(entrypoint))
            || !Files.isRegularFile(path)
            || !(lower.endsWith(".js") || lower.endsWith(".mjs")
            || lower.endsWith(".cjs"))) {
            throw error(owner, "entrypoint must be a JavaScript file inside payload",
                null);
        }
    }

    private static List<NodeEndpointManifest> contributions(Object raw,
                                                              ArtifactId owner) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw error(owner, "contributions must be a non-empty array", null);
        }
        var result = new ArrayList<NodeEndpointManifest>();
        var names = new java.util.HashSet<String>();
        for (var item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw error(owner, "every contribution must be an object", null);
            }
            var values = stringMap(map, owner);
            rejectUnknown(values, ENDPOINT_FIELDS, owner);
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
        return result;
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw, ArtifactId owner) {
        var result = new LinkedHashMap<String, Object>();
        raw.forEach((key, value) -> {
            if (!(key instanceof String name)) {
                throw error(owner, "manifest keys must be strings", null);
            }
            result.put(name, value);
        });
        return result;
    }

    private static void rejectUnknown(Map<String, Object> values, Set<String> allowed,
                                      ArtifactId owner) {
        var unknown = values.keySet().stream().filter(key -> !allowed.contains(key)).toList();
        if (!unknown.isEmpty()) {
            throw error(owner, "unknown manifest fields " + unknown, null);
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
