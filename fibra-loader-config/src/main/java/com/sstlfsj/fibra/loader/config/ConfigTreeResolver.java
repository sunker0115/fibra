package com.sstlfsj.fibra.loader.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

final class ConfigTreeResolver {
    private static final Set<String> PLUGIN_FIELDS = Set.of(
        "id", "name", "disabled", "inject", "intercept", "isolate", "config");
    private static final Set<String> GROUP_FIELDS = Set.of(
        "id", "group", "disabled", "intercept", "isolate", "config");
    private static final Set<String> INCLUDE_FIELDS = Set.of(
        "id", "include", "disabled", "intercept", "isolate", "patches");
    private static final Set<String> PATCH_FIELDS = Set.of(
        "id", "insert", "name", "config", "group", "disabled", "inject",
        "intercept", "isolate");

    private final ConfigLimits limits;
    private final ConfigDocumentReader reader;
    private final ConfigPatchApplier patchApplier = new ConfigPatchApplier();
    private final Consumer<FibraConfigWarning> warningSink;
    private final LinkedHashSet<Path> attemptedPaths = new LinkedHashSet<>();

    ConfigTreeResolver(ConfigLimits limits, Consumer<FibraConfigWarning> warningSink) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.reader = new ConfigDocumentReader(limits);
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
    }

    List<Map<String, Object>> read(Path path) {
        return reader.read(path);
    }

    void validateSerialized(Path path, byte[] bytes) {
        validateLiteralLimits(reader.read(path, bytes), path, 1);
    }

    Set<Path> attemptedPaths() {
        return Set.copyOf(attemptedPaths);
    }

    FibraConfigSnapshot resolve(Path path, List<FibraConfigPatch> patches) {
        return resolve(path, patches, Map.of());
    }

    FibraConfigSnapshot resolve(Path path, List<FibraConfigPatch> patches,
                                Map<Path, List<Map<String, Object>>> overrides) {
        Objects.requireNonNull(patches, "patches");
        Objects.requireNonNull(overrides, "overrides");
        attemptedPaths.clear();
        var stack = new LinkedHashSet<Path>();
        var root = resolvePath(path, null);
        var normalizedOverrides = new LinkedHashMap<Path, List<Map<String, Object>>>();
        overrides.forEach((key, value) -> normalizedOverrides.put(
            key.toAbsolutePath().normalize(), LiteralValues.freezeEntries(value)));
        var entries = resolveFile(root, "", false, Map.of(), Map.of(), patches, stack,
            normalizedOverrides);
        try {
            return new FibraConfigSnapshot(root, entries);
        } catch (IllegalArgumentException exception) {
            throw error(root, null, null, exception.getMessage(), exception);
        }
    }

    private List<FibraConfigEntry> resolveFile(Path path, String parentId,
                                               boolean parentDisabled,
                                               Map<String, Object> parentIsolate,
                                               Map<String, Object> parentIntercept,
                                               List<FibraConfigPatch> patches,
                                               LinkedHashSet<Path> stack,
                                               Map<Path, List<Map<String, Object>>> overrides) {
        if (!stack.add(path)) {
            throw error(path, parentId, null, "include cycle detected at " + path, null);
        }
        try {
            var document = overrides.get(path);
            if (document == null) {
                document = reader.read(path);
            }
            var raw = patchApplier.apply(document, patches, warningSink);
            if (countEntries(raw) > limits.maxEntriesPerFile()) {
                throw error(path, parentId, null,
                    "config file exceeds " + limits.maxEntriesPerFile() + " entries", null);
            }
            return resolveEntries(raw, path, parentId, "", parentDisabled, parentIsolate,
                parentIntercept, stack, overrides);
        } finally {
            stack.remove(path);
        }
    }

    private List<FibraConfigEntry> resolveEntries(List<Map<String, Object>> raw, Path source,
                                                  String parentId, String sourceParentId,
                                                  boolean parentDisabled,
                                                  Map<String, Object> parentIsolate,
                                                  Map<String, Object> parentIntercept,
                                                  LinkedHashSet<Path> stack,
                                                  Map<Path, List<Map<String, Object>>> overrides) {
        var ids = new LinkedHashSet<String>();
        var result = new ArrayList<FibraConfigEntry>(raw.size());
        for (var values : raw) {
            var id = text(values.get("id"), "id", source, parentId, null);
            if (id.indexOf(':') >= 0) {
                throw error(source, complete(parentId, id), null,
                    "raw entry id must not contain ':'", null);
            }
            if (!ids.add(id)) {
                throw error(source, complete(parentId, id), null,
                    "duplicate entry id " + id + " in the same group", null);
            }
            var entryId = complete(parentId, id);
            var sourceEntryId = complete(sourceParentId, id);
            result.add(resolveEntry(values, source, id, entryId, sourceEntryId,
                parentDisabled, parentIsolate, parentIntercept, stack, overrides));
        }
        return List.copyOf(result);
    }

    private FibraConfigEntry resolveEntry(Map<String, Object> values, Path source,
                                          String id, String entryId, String sourceEntryId,
                                          boolean parentDisabled,
                                          Map<String, Object> parentIsolate,
                                          Map<String, Object> parentIntercept,
                                          LinkedHashSet<Path> stack,
                                          Map<Path, List<Map<String, Object>>> overrides) {
        var kind = kind(values, source, entryId);
        validateFields(values, fields(kind), source, entryId);
        var declaredDisabled = bool(values.get("disabled"), "disabled", false,
            source, entryId, null);
        var localIsolate = isolate(values.get("isolate"), source, entryId);
        var localIntercept = object(values.get("intercept"), "intercept",
            source, entryId, null);
        var isolate = merge(parentIsolate, localIsolate);
        var intercept = merge(parentIntercept, localIntercept);
        var builder = new FibraConfigEntry.Builder()
            .id(id)
            .entryId(entryId)
            .sourceEntryId(sourceEntryId)
            .kind(kind)
            .source(source)
            .declaredDisabled(declaredDisabled)
            .localIsolate(localIsolate)
            .localIntercept(localIntercept)
            .isolate(isolate)
            .intercept(intercept);

        if (kind == FibraConfigEntry.Kind.PLUGIN) {
            var pluginId = text(values.get("name"), "name", source, entryId, null);
            return builder.pluginId(pluginId)
                .disabled(parentDisabled || declaredDisabled)
                .config(values.get("config"))
                .inject(inject(values.get("inject"), source, entryId, pluginId))
                .build();
        }

        var descendantsDisabled = parentDisabled || declaredDisabled;
        if (kind == FibraConfigEntry.Kind.GROUP) {
            var children = entryList(values.get("config"), "group config", source, entryId);
            return builder.disabled(false)
                .children(resolveEntries(children, source, entryId, sourceEntryId,
                    descendantsDisabled, isolate, intercept, stack, overrides))
                .build();
        }

        var include = text(values.get("include"), "include", source, entryId, null);
        var includedPath = resolvePath(source.getParent().resolve(include), entryId);
        if (stack.contains(includedPath)) {
            throw error(includedPath, entryId, null,
                "include cycle detected at " + includedPath, null);
        }
        var patches = patches(values.get("patches"), source, entryId);
        return builder.disabled(false)
            .includedPath(includedPath)
            .children(resolveFile(includedPath, entryId, descendantsDisabled, isolate,
                intercept, patches, stack, overrides))
            .build();
    }

    private List<FibraConfigPatch> patches(Object value, Path source, String entryId) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw error(source, entryId, null, "patches must be an array", null);
        }
        var result = new ArrayList<FibraConfigPatch>(list.size());
        for (var item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw error(source, entryId, null, "every patch must be an object", null);
            }
            var patch = stringMap(raw, source, entryId, null);
            validateFields(patch, PATCH_FIELDS, source, entryId);
            if (patch.containsKey("insert")) {
                var target = optionalText(patch.get("id"), "patch id", source, entryId, null);
                if (patch.size() != (target == null ? 1 : 2)) {
                    throw error(source, entryId, null,
                        "insert patch only accepts id and insert", null);
                }
                result.add(FibraConfigPatch.insert(target,
                    entryList(patch.get("insert"), "patch insert", source, entryId)));
            } else {
                var target = text(patch.get("id"), "patch id", source, entryId, null);
                var expected = optionalText(patch.get("name"), "patch name",
                    source, entryId, null);
                var fields = new LinkedHashMap<String, Object>(patch);
                fields.remove("id");
                fields.remove("name");
                if (fields.isEmpty()) {
                    throw error(source, entryId, null,
                        "override patch must contain at least one field", null);
                }
                result.add(FibraConfigPatch.override(target, expected, fields));
            }
        }
        return List.copyOf(result);
    }

    private static FibraConfigEntry.Kind kind(Map<String, Object> values, Path source,
                                               String entryId) {
        if (values.containsKey("group")) {
            if (!Boolean.TRUE.equals(values.get("group"))) {
                throw error(source, entryId, null, "group must be true when present", null);
            }
            return FibraConfigEntry.Kind.GROUP;
        }
        if (values.containsKey("include")) {
            return FibraConfigEntry.Kind.INCLUDE;
        }
        if (values.containsKey("name")) {
            return FibraConfigEntry.Kind.PLUGIN;
        }
        throw error(source, entryId, null,
            "entry must declare exactly one of name, group: true or include", null);
    }

    private static Set<String> fields(FibraConfigEntry.Kind kind) {
        return switch (kind) {
            case PLUGIN -> PLUGIN_FIELDS;
            case GROUP -> GROUP_FIELDS;
            case INCLUDE -> INCLUDE_FIELDS;
        };
    }

    private static void validateFields(Map<String, Object> values, Set<String> allowed,
                                       Path source, String entryId) {
        var unknown = values.keySet().stream().filter(key -> !allowed.contains(key)).toList();
        if (!unknown.isEmpty()) {
            throw error(source, entryId, null, "unknown fields " + unknown, null);
        }
    }

    private static Map<String, Object> inject(Object value, Path source, String entryId,
                                              String pluginId) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof List<?> list) {
            var result = new LinkedHashMap<String, Object>();
            for (var item : list) {
                var name = text(item, "inject service", source, entryId, pluginId);
                if (result.containsKey(name)) {
                    throw error(source, entryId, pluginId,
                        "duplicate inject service " + name, null);
                }
                result.put(name, null);
            }
            return result;
        }
        return object(value, "inject", source, entryId, pluginId);
    }

    private static Map<String, Object> isolate(Object value, Path source, String entryId) {
        var result = object(value, "isolate", source, entryId, null);
        result.forEach((name, label) -> {
            if (!Boolean.TRUE.equals(label)
                && (!(label instanceof String text) || text.isBlank())) {
                throw error(source, entryId, null,
                    "isolate value for " + name + " must be true or a non-blank string", null);
            }
        });
        return result;
    }

    private static Map<String, Object> object(Object value, String field, Path source,
                                              String entryId, String pluginId) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw error(source, entryId, pluginId, field + " must be an object", null);
        }
        var result = stringMap(raw, source, entryId, pluginId);
        for (var name : result.keySet()) {
            if (name.isBlank()) {
                throw error(source, entryId, pluginId,
                    field + " service names must not be blank", null);
            }
        }
        return result;
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw, Path source,
                                                 String entryId, String pluginId) {
        var result = new LinkedHashMap<String, Object>();
        raw.forEach((key, value) -> {
            if (!(key instanceof String name)) {
                throw error(source, entryId, pluginId,
                    "object keys must be strings", null);
            }
            result.put(name, value);
        });
        return result;
    }

    private static List<Map<String, Object>> entryList(Object value, String field,
                                                       Path source, String entryId) {
        if (!(value instanceof List<?> list)) {
            throw error(source, entryId, null, field + " must be an array", null);
        }
        var result = new ArrayList<Map<String, Object>>(list.size());
        for (var item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw error(source, entryId, null,
                    field + " entries must be objects", null);
            }
            result.add(stringMap(raw, source, entryId, null));
        }
        return result;
    }

    private static Map<String, Object> merge(Map<String, Object> parent,
                                             Map<String, Object> local) {
        if (parent.isEmpty()) {
            return local;
        }
        var result = new LinkedHashMap<String, Object>(parent);
        result.putAll(local);
        return result;
    }

    private static boolean bool(Object value, String field, boolean defaultValue,
                                Path source, String entryId, String pluginId) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean result)) {
            throw error(source, entryId, pluginId, field + " must be boolean", null);
        }
        return result;
    }

    private static String optionalText(Object value, String field, Path source,
                                       String entryId, String pluginId) {
        return value == null ? null : text(value, field, source, entryId, pluginId);
    }

    private static String text(Object value, String field, Path source,
                               String entryId, String pluginId) {
        if (!(value instanceof String result) || result.isBlank()) {
            throw error(source, entryId, pluginId,
                field + " must be a non-blank string", null);
        }
        return result;
    }

    private static int countEntries(List<Map<String, Object>> entries) {
        int result = entries.size();
        for (var entry : entries) {
            if (Boolean.TRUE.equals(entry.get("group"))
                && entry.get("config") instanceof List<?> children) {
                @SuppressWarnings("unchecked")
                var typed = (List<Map<String, Object>>) children;
                result += countEntries(typed);
            }
        }
        return result;
    }

    private void validateLiteralLimits(Object value, Path path, int depth) {
        if (depth > limits.maxDepth()) {
            throw error(path, null, null,
                "config exceeds nesting depth " + limits.maxDepth(), null);
        }
        if (value instanceof String text && text.length() > limits.maxStringLength()) {
            throw error(path, null, null,
                "config string exceeds " + limits.maxStringLength() + " characters", null);
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> {
                validateLiteralLimits(key, path, depth + 1);
                validateLiteralLimits(nested, path, depth + 1);
            });
        } else if (value instanceof List<?> list) {
            list.forEach(nested -> validateLiteralLimits(nested, path, depth + 1));
        }
    }

    private static String complete(String parentId, String id) {
        return parentId == null || parentId.isEmpty() ? id : parentId + ':' + id;
    }

    private Path resolvePath(Path path, String entryId) {
        var normalized = path.toAbsolutePath().normalize();
        attemptedPaths.add(normalized);
        try {
            var real = normalized.toRealPath();
            attemptedPaths.add(real);
            return real;
        } catch (IOException exception) {
            throw new FibraConfigException(FibraConfigErrorStage.READ,
                "cannot resolve config file " + normalized, normalized,
                entryId, null, exception);
        }
    }

    private static FibraConfigException error(Path path, String entryId, String pluginId,
                                              String message, Throwable cause) {
        return new FibraConfigException(FibraConfigErrorStage.VALIDATE, message, path,
            entryId, pluginId, cause);
    }
}
