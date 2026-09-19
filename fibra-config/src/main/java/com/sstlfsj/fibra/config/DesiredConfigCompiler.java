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
        "id", "plugin", "enabled", "when", "context", "publication", "config", "realm", "intercept");
    private static final Set<String> GROUP_FIELDS = Set.of(
        "id", "group", "enabled", "when", "context", "entries", "realm", "intercept");
    private static final Set<String> INCLUDE_FIELDS = Set.of(
        "id", "include", "enabled", "when", "context", "patches", "realm", "intercept");

    private final ConfigDocumentReader reader;
    private final ConfigPatchApplier patchApplier = new ConfigPatchApplier();

    public DesiredConfigCompiler(ConfigLimits limits) {
        reader = new ConfigDocumentReader(Objects.requireNonNull(limits, "limits"));
    }

    public DesiredCompilation compile(Path root) {
        Objects.requireNonNull(root, "root");
        var state = new CompilationState();
        var document = reader.read(root, null);
        var roots = state.resolveDocument(document, "", true, null);
        return DesiredCompilation.builder().snapshot(new DesiredSourceSnapshot(document.path().toString(),
            revision(state.sources), state.sources.keySet())).graph(new DesiredInputGraph(roots))
            .entrySources(state.entrySources).diagnostics(state.diagnostics).build();
    }

    private final class CompilationState {
        private final LinkedHashMap<Path, byte[]> sources = new LinkedHashMap<>();
        private final Map<String, Path> entrySources = new LinkedHashMap<>();
        private final LinkedHashSet<Path> stack = new LinkedHashSet<>();
        private final List<ConfigDiagnostic> diagnostics = new ArrayList<>();

        private List<DesiredInputNode> resolveDocument(ConfigDocumentReader.Document document,
                                                       String namespace, boolean parentEnabled,
                                                       Object patches) {
            var path = document.path();
            if (!stack.add(path)) {
                throw error(ConfigStage.RESOLVE, "INCLUDE_CYCLE", "include cycle detected at " + path,
                    path, namespace, null);
            }
            sources.putIfAbsent(path, document.bytes());
            try {
                return resolveEntries(patchApplier.apply(document.entries(), patches, path, namespace,
                    diagnostics::add), path, namespace, parentEnabled);
            } finally {
                stack.remove(path);
            }
        }

        private List<DesiredInputNode> resolveEntries(List<Map<String, Object>> values, Path source,
                                                      String namespace, boolean parentEnabled) {
            var result = new ArrayList<DesiredInputNode>(values.size());
            for (var value : values) {
                var rawId = text(value.get("id"), "id", source, namespace);
                if (rawId.indexOf(':') >= 0) {
                    throw error(ConfigStage.VALIDATE, "INVALID_ID", "raw entry id must not contain ':'",
                        source, complete(namespace, rawId), null);
                }
                var fullId = complete(namespace, rawId);
                if (entrySources.putIfAbsent(fullId, source) != null) {
                    throw error(ConfigStage.VALIDATE, "DUPLICATE_ID", "duplicate entry id " + fullId,
                        source, fullId, null);
                }
                result.add(resolveEntry(value, source, rawId, fullId, namespace, parentEnabled));
            }
            return List.copyOf(result);
        }

        private DesiredInputNode resolveEntry(Map<String, Object> value, Path source, String rawId,
                                              String fullId, String namespace, boolean parentEnabled) {
            var kind = kind(value, source, fullId);
            validateFields(value, fields(kind), source, fullId);
            var localEnabled = bool(value.get("enabled"), true, "enabled", source, fullId);
            var effectiveEnabled = parentEnabled && localEnabled;
            var when = LiteralValue.of(value.getOrDefault("when", true));
            validateExpression(when, true, source, fullId);
            var context = context(value.get("context"), source, fullId);
            var realms = realms(object(value.get("realm"), "realm", source, fullId), source, fullId);
            var intercepts = literals(object(value.get("intercept"), "intercept", source, fullId));
            if (kind == Kind.PLUGIN) {
                var config = LiteralValue.of(value.get("config"));
                validateExpression(config, false, source, fullId);
                return DesiredInputEntry.builder(rawId, definitionRef(value.get("plugin"), source, fullId))
                    .enabled(localEnabled).when(when).context(context)
                    .publicationRequirement(publicationRequirement(value.get("publication"),
                        source, fullId)).config(config).realms(realms)
                    .intercepts(intercepts).build();
            }
            if (kind == Kind.GROUP) {
                return DesiredInputGroup.builder(rawId).enabled(localEnabled).when(when).context(context)
                    .realms(realms)
                    .intercepts(intercepts).children(resolveEntries(entries(value.get("entries"), "entries",
                        source, fullId), source, namespace, effectiveEnabled)).build();
            }
            var include = text(value.get("include"), "include", source, fullId);
            if (!effectiveEnabled) {
                return DesiredInputInclude.builder(rawId).enabled(localEnabled).when(when).context(context)
                    .realms(realms)
                    .intercepts(intercepts).content(DesiredIncludeContent.Uncollected.INSTANCE).build();
            }
            var included = reader.read(source.getParent().resolve(include), fullId);
            if (stack.contains(included.path())) {
                throw error(ConfigStage.RESOLVE, "INCLUDE_CYCLE", "include cycle detected at " + included.path(),
                    included.path(), fullId, null);
            }
            return DesiredInputInclude.builder(rawId).enabled(localEnabled).when(when).context(context)
                .realms(realms)
                .intercepts(intercepts).content(new DesiredIncludeContent.Collected(
                    resolveDocument(included, fullId, effectiveEnabled, value.get("patches")))).build();
        }
    }

    private static Map<String, LiteralValue> realms(Map<String, Object> values, Path source,
                                                     String entryId) {
        try {
            return PolicyValues.realms(literals(values));
        } catch (IllegalArgumentException exception) {
            throw error(ConfigStage.VALIDATE, "REALM_POLICY_INVALID", exception.getMessage(), source,
                entryId, exception);
        }
    }

    private static Map<String, LiteralValue> literals(Map<String, Object> values) {
        return ((LiteralValue.ObjectValue) LiteralValue.of(values)).values();
    }

    private static Map<String, LiteralValue> context(Object value, Path source, String entryId) {
        var result = literals(object(value, "context", source, entryId));
        if (result.containsKey("entry")) throw error(ConfigStage.VALIDATE,
            "CONTEXT_ENTRY_RESERVED", "top-level context key 'entry' is reserved",
            source, entryId, null);
        return result;
    }

    private static PluginDefinitionRef definitionRef(Object value, Path source, String entryId) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw error(ConfigStage.VALIDATE, "FIELD_NOT_OBJECT", "plugin must be an object", source,
                entryId, null);
        }
        var fields = LiteralValues.freezeMap(raw);
        var unknown = fields.keySet().stream()
            .filter(key -> !Set.of("id", "facet", "definition").contains(key)).toList();
        if (!unknown.isEmpty()) {
            throw error(ConfigStage.VALIDATE, "PLUGIN_REFERENCE_FIELDS_INVALID",
                "unknown plugin reference fields " + unknown, source, entryId, null);
        }
        return new PluginDefinitionRef(text(fields.get("id"), "plugin.id", source, entryId),
            text(fields.get("facet"), "plugin.facet", source, entryId),
            text(fields.get("definition"), "plugin.definition", source, entryId));
    }

    private static void validateExpression(LiteralValue value, boolean condition,
                                           Path source, String entryId) {
        try {
            if (condition) ConfigExpressionEvaluator.validateCondition(value);
            else ConfigExpressionEvaluator.validateTemplate(value);
        } catch (ConfigException failure) {
            throw error(ConfigStage.VALIDATE, failure.diagnostic().code(),
                failure.diagnostic().message(), source, entryId, failure);
        }
    }

    private static Kind kind(Map<String, Object> value, Path source, String entryId) {
        var plugin = value.containsKey("plugin");
        var group = value.containsKey("group");
        var include = value.containsKey("include");
        if ((plugin ? 1 : 0) + (group ? 1 : 0) + (include ? 1 : 0) != 1) {
            throw error(ConfigStage.VALIDATE, "ENTRY_KIND_INVALID",
                "entry must declare exactly one of plugin, group: true or include", source, entryId, null);
        }
        if (group && !Boolean.TRUE.equals(value.get("group"))) {
            throw error(ConfigStage.VALIDATE, "GROUP_NOT_TRUE", "group must be true when present", source,
                entryId, null);
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
        if (!unknown.isEmpty()) throw error(ConfigStage.VALIDATE, "UNKNOWN_FIELDS",
            "unknown fields " + unknown, source, entryId, null);
    }

    private static List<Map<String, Object>> entries(Object value, String field, Path source,
                                                     String entryId) {
        if (!(value instanceof List<?> list)) throw error(ConfigStage.VALIDATE, "FIELD_NOT_ARRAY",
            field + " must be an array", source, entryId, null);
        try {
            for (var entry : list) {
                if (!(entry instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException("every config entry must be an object");
                }
            }
            return LiteralValues.freezeEntries(list);
        } catch (IllegalArgumentException exception) {
            throw error(ConfigStage.VALIDATE, "INVALID_LITERAL", exception.getMessage(), source, entryId,
                exception);
        }
    }

    private static Map<String, Object> object(Object value, String field, Path source,
                                              String entryId) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map)) throw error(ConfigStage.VALIDATE, "FIELD_NOT_OBJECT",
            field + " must be an object", source, entryId, null);
        var result = LiteralValues.freezeMap(map);
        if (result.keySet().stream().anyMatch(String::isBlank)) throw error(ConfigStage.VALIDATE,
            "BLANK_POLICY_KEY", field + " keys must not be blank", source, entryId, null);
        return result;
    }

    private static String text(Object value, String field, Path source, String entryId) {
        if (!(value instanceof String text) || text.isBlank()) throw error(ConfigStage.VALIDATE,
            "FIELD_NOT_TEXT", field + " must be a non-blank string", source, entryId, null);
        return text;
    }

    private static boolean bool(Object value, boolean defaultValue, String field, Path source,
                                String entryId) {
        if (value == null) return defaultValue;
        if (!(value instanceof Boolean result)) throw error(ConfigStage.VALIDATE, "FIELD_NOT_BOOLEAN",
            field + " must be boolean", source, entryId, null);
        return result;
    }

    private static PublicationRequirement publicationRequirement(Object value, Path source,
                                                                 String entryId) {
        if (value == null || "active-required".equals(value)) return PublicationRequirement.ACTIVE_REQUIRED;
        if ("pending-allowed".equals(value)) return PublicationRequirement.PENDING_ALLOWED;
        throw error(ConfigStage.VALIDATE, "PUBLICATION_REQUIREMENT_INVALID",
            "publication must be active-required or pending-allowed", source, entryId, null);
    }

    private static String complete(String namespace, String id) {
        return namespace.isEmpty() ? id : namespace + ':' + id;
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
        return new ConfigException(new ConfigDiagnostic(stage, code, message, source, entryId), cause);
    }

    private enum Kind { PLUGIN, GROUP, INCLUDE }
}
