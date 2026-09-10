package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;

final class JavaManifestReader {
    static final String LOCATION = "META-INF/fibra/plugin.yaml";
    private static final Set<String> FIELDS = Set.of(
        "id", "version", "entrypoint", "requires");
    private static final Set<String> REQUIREMENT_FIELDS = Set.of("id", "version");

    private final YAMLMapper yaml = YAMLMapper.builder(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .build();

    JavaPluginManifest read(ArtifactRecord artifact) {
        try (var jar = new JarFile(artifact.location().toFile(), true)) {
            var entry = jar.getJarEntry(LOCATION);
            if (entry == null) {
                throw error(artifact.id(), "missing " + LOCATION, null);
            }
            if (entry.getSize() > 64 * 1024) {
                throw error(artifact.id(), "Java plugin manifest is too large", null);
            }
            Object raw;
            try (var input = jar.getInputStream(entry)) {
                raw = yaml.readValue(input, Object.class);
            }
            if (!(raw instanceof Map<?, ?> map)) {
                throw error(artifact.id(), "Java plugin manifest must be an object", null);
            }
            var values = stringMap(map, artifact.id());
            rejectUnknown(values, FIELDS, artifact.id());
            var id = new ArtifactId(text(values.get("id"), "id", artifact.id()));
            var version = text(values.get("version"), "version", artifact.id());
            var entrypoint = optionalText(values.get("entrypoint"), "entrypoint",
                artifact.id());
            if (!id.equals(artifact.id())) {
                throw error(artifact.id(), "manifest id does not match artifact id", null);
            }
            if (!version.equals(artifact.version())) {
                throw error(artifact.id(), "manifest version does not match artifact version", null);
            }
            return new JavaPluginManifest(id, version, entrypoint,
                requirements(values.get("requires"), artifact.id()));
        } catch (JavaRuntimeException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw error(artifact.id(), "cannot read Java plugin manifest", exception);
        }
    }

    private static List<JavaArtifactRequirement> requirements(Object value,
                                                              ArtifactId owner) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw error(owner, "requires must be an array", null);
        }
        var result = new ArrayList<JavaArtifactRequirement>();
        for (var item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw error(owner, "every requirement must be an object", null);
            }
            var requirement = stringMap(raw, owner);
            rejectUnknown(requirement, REQUIREMENT_FIELDS, owner);
            result.add(new JavaArtifactRequirement(
                new ArtifactId(text(requirement.get("id"), "requires.id", owner)),
                text(requirement.get("version"), "requires.version", owner)));
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

    private static Optional<String> optionalText(Object value, String field,
                                                 ArtifactId owner) {
        return value == null ? Optional.empty()
            : Optional.of(text(value, field, owner));
    }

    private static JavaRuntimeException error(ArtifactId id, String message,
                                              Throwable cause) {
        return new JavaRuntimeException(JavaRuntimePhase.MANIFEST, id, message, cause);
    }
}
