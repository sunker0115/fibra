package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class DesiredConfigCompiler {
    private static final Set<String> PLUGIN_FIELDS = Set.of(
        "id", "plugin", "enabled", "publication", "config", "realm", "intercept");
    private static final Set<String> GROUP_FIELDS = Set.of(
        "id", "group", "enabled", "entries", "realm", "intercept");
    private static final Set<String> INCLUDE_FIELDS = Set.of(
        "id", "include", "enabled", "patches", "realm", "intercept");

    private final ConfigLimits limits;
    private final ConfigDocumentReader reader;
    private final ConfigPatchApplier patchApplier = new ConfigPatchApplier();

    public DesiredConfigCompiler(ConfigLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        reader = new ConfigDocumentReader(limits);
    }

    public DesiredCompilation compile(Path root) {
        Objects.requireNonNull(root, "root");
        var state = new CompilationState();
        var rootDocument = reader.read(root, null);
        state.resolveDocument(rootDocument, "", true, Map.of(), Map.of(), null);
        return DesiredCompilation.builder()
            .snapshot(new DesiredSourceSnapshot(rootDocument.path().toString(),
                revision(state.sources), state.sources.keySet()))
            .graph(new DesiredInputGraph(state.entries)).entrySources(state.entrySources).build();
    }

    private final class CompilationState {
        private final LinkedHashMap<Path, byte[]> sources = new LinkedHashMap<>();
        private final Map<String, Path> entrySources = new LinkedHashMap<>();
        private final LinkedHashSet<Path> stack = new LinkedHashSet<>();
        private final List<DesiredInputEntry> entries = new ArrayList<>();
        private final Set<String> ids = new LinkedHashSet<>();

        private void resolveDocument(ConfigDocumentReader.Document document,
                                     String parentId, boolean parentEnabled,
                                     Map<String, Object> parentRealms,
                                     Map<String, Object> parentIntercepts,
                                     Object patches) {
            var path = document.path();
            if (!stack.add(path)) {
                throw error(ConfigStage.RESOLVE, "INCLUDE_CYCLE",
                    "include cycle detected at " + path, path, parentId, null);
            }
            sources.putIfAbsent(path, document.bytes());
            try {
                var values = patchApplier.apply(document.entries(), patches, path, parentId);
                resolveEntries(values, path, parentId, parentEnabled,
                    parentRealms, parentIntercepts);
            } finally {
                stack.remove(path);
            }
        }

        private void resolveEntries(List<Map<String, Object>> values, Path source,
                                    String parentId, boolean parentEnabled,
                                    Map<String, Object> parentRealms,
                                    Map<String, Object> parentIntercepts) {
            for (var value : values) {
                var rawId = text(value.get("id"), "id", source, parentId);
                if (rawId.indexOf(':') >= 0) {
                    throw error(ConfigStage.VALIDATE, "INVALID_ID",
                        "raw entry id must not contain ':'", source,
                        complete(parentId, rawId), null);
                }
                var entryId = complete(parentId, rawId);
                if (!ids.add(entryId)) {
                    throw error(ConfigStage.VALIDATE, "DUPLICATE_ID",
                        "duplicate entry id " + entryId, source, entryId, null);
                }
                resolveEntry(value, source, entryId, parentEnabled,
                    parentRealms, parentIntercepts);
            }
        }

        private void resolveEntry(Map<String, Object> value, Path source, String entryId,
                                  boolean parentEnabled,
                                  Map<String, Object> parentRealms,
                                  Map<String, Object> parentIntercepts) {
            var kind = kind(value, source, entryId);
            validateFields(value, fields(kind), source, entryId);
            var enabled = parentEnabled && bool(value.get("enabled"), true, "enabled",
                source, entryId);
            var realms = merge(parentRealms,
                object(value.get("realm"), "realm", source, entryId));
            var intercepts = merge(parentIntercepts,
                object(value.get("intercept"), "intercept", source, entryId));
            if (kind == Kind.GROUP) {
                var children = entries(value.get("entries"), "entries", source, entryId);
                resolveEntries(children, source, entryId, enabled, realms, intercepts);
                return;
            }
            if (kind == Kind.INCLUDE) {
                var include = text(value.get("include"), "include", source, entryId);
                var included = reader.read(source.getParent().resolve(include), entryId);
                if (stack.contains(included.path())) {
                    throw error(ConfigStage.RESOLVE, "INCLUDE_CYCLE",
                        "include cycle detected at " + included.path(), included.path(),
                        entryId, null);
                }
                resolveDocument(included, entryId, enabled, realms, intercepts,
                    value.get("patches"));
                return;
            }

            var definitionName = text(value.get("plugin"), "plugin", source, entryId);
            entries.add(DesiredInputEntry.builder(entryId, definitionName).enabled(enabled)
                .publicationRequirement(publicationRequirement(value.get("publication"),
                    source, entryId))
                .config(LiteralValue.of(value.get("config")))
                .realms(literals(realms)).intercepts(literals(intercepts)).build());
            entrySources.put(entryId, source);
        }
    }

    private static Map<String, LiteralValue> literals(Map<String, Object> values) {
        return ((LiteralValue.ObjectValue) LiteralValue.of(values)).values();
    }

    private static Kind kind(Map<String, Object> value, Path source, String entryId) {
        var plugin = value.containsKey("plugin");
        var group = value.containsKey("group");
        var include = value.containsKey("include");
        if ((plugin ? 1 : 0) + (group ? 1 : 0) + (include ? 1 : 0) != 1) {
            throw error(ConfigStage.VALIDATE, "ENTRY_KIND_INVALID",
                "entry must declare exactly one of plugin, group: true or include",
                source, entryId, null);
        }
        if (group && !Boolean.TRUE.equals(value.get("group"))) {
            throw error(ConfigStage.VALIDATE, "GROUP_NOT_TRUE",
                "group must be true when present", source, entryId, null);
        }
        return plugin ? Kind.PLUGIN : group ? Kind.GROUP : Kind.INCLUDE;
    }

    private static Set<String> fields(Kind kind) {
        return switch (kind) {
            case PLUGIN -> PLUGIN_FIELDS;
            case GROUP -> GROUP_FIELDS;
            case INCLUDE -> INCLUDE_FIELDS;
        };
    }

    private static void validateFields(Map<String, Object> value, Set<String> allowed,
                                       Path source, String entryId) {
        var unknown = value.keySet().stream().filter(key -> !allowed.contains(key)).toList();
        if (!unknown.isEmpty()) {
            throw error(ConfigStage.VALIDATE, "UNKNOWN_FIELDS",
                "unknown fields " + unknown, source, entryId, null);
        }
    }

    private static List<Map<String, Object>> entries(Object value, String field,
                                                     Path source, String entryId) {
        if (!(value instanceof List<?> list)) {
            throw error(ConfigStage.VALIDATE, "FIELD_NOT_ARRAY",
                field + " must be an array", source, entryId, null);
        }
        try {
            return LiteralValues.freezeEntries(list);
        } catch (IllegalArgumentException exception) {
            throw error(ConfigStage.VALIDATE, "INVALID_LITERAL",
                exception.getMessage(), source, entryId, exception);
        }
    }

    private static Map<String, Object> object(Object value, String field,
                                              Path source, String entryId) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw error(ConfigStage.VALIDATE, "FIELD_NOT_OBJECT",
                field + " must be an object", source, entryId, null);
        }
        var result = LiteralValues.freezeMap(map);
        if (result.keySet().stream().anyMatch(String::isBlank)) {
            throw error(ConfigStage.VALIDATE, "BLANK_POLICY_KEY",
                field + " keys must not be blank", source, entryId, null);
        }
        return result;
    }

    private static Map<String, Object> merge(Map<String, Object> parent,
                                             Map<String, Object> local) {
        if (parent.isEmpty()) {
            return local;
        }
        var merged = new LinkedHashMap<String, Object>(parent);
        merged.putAll(local);
        return LiteralValues.freezeMap(merged);
    }

    private static String text(Object value, String field, Path source, String entryId) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw error(ConfigStage.VALIDATE, "FIELD_NOT_TEXT",
                field + " must be a non-blank string", source, entryId, null);
        }
        return text;
    }

    private static boolean bool(Object value, boolean defaultValue, String field,
                                Path source, String entryId) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean result)) {
            throw error(ConfigStage.VALIDATE, "FIELD_NOT_BOOLEAN",
                field + " must be boolean", source, entryId, null);
        }
        return result;
    }

    private static PublicationRequirement publicationRequirement(
        Object value, Path source, String entryId) {
        if (value == null || "active-required".equals(value)) {
            return PublicationRequirement.ACTIVE_REQUIRED;
        }
        if ("pending-allowed".equals(value)) {
            return PublicationRequirement.PENDING_ALLOWED;
        }
        throw error(ConfigStage.VALIDATE, "PUBLICATION_REQUIREMENT_INVALID",
            "publication must be active-required or pending-allowed",
            source, entryId, null);
    }

    private static String complete(String parent, String id) {
        return parent == null || parent.isEmpty() ? id : parent + ':' + id;
    }

    private static String revision(LinkedHashMap<Path, byte[]> sources) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            sources.forEach((path, bytes) -> {
                digest.update(path.toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(bytes);
                digest.update((byte) 0);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ConfigException error(ConfigStage stage, String code, String message,
                                         Path source, String entryId, Throwable cause) {
        return new ConfigException(
            new ConfigDiagnostic(stage, code, message, source, entryId), cause);
    }

    private enum Kind {
        PLUGIN,
        GROUP,
        INCLUDE
    }
}
