package com.sstlfsj.fibra.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class ConfigPatchApplier {
    private static final Set<String> FIELDS = Set.of("id", "plugin", "insert", "enabled",
        "publication", "config", "realm", "intercept", "group", "entries", "include", "patches");

    List<Map<String, Object>> apply(List<Map<String, Object>> source, Object rawPatches,
                                    Path path, String includeId, Consumer<ConfigDiagnostic> warnings) {
        @SuppressWarnings("unchecked")
        var result = (List<Map<String, Object>>) (List<?>) LiteralValues.mutable(source);
        if (rawPatches == null) {
            return LiteralValues.freezeEntries(result);
        }
        if (!(rawPatches instanceof List<?> patches)) {
            throw error("PATCHES_NOT_ARRAY", "patches must be an array", path, includeId);
        }
        var index = new LinkedHashMap<String, Map<String, Object>>();
        index(result, index);
        for (int position = 0; position < patches.size(); position++) {
            var item = patches.get(position);
            if (!(item instanceof Map<?, ?> raw)) {
                throw error("PATCH_NOT_OBJECT", "every patch must be an object", path, includeId);
            }
            var patch = LiteralValues.freezeMap(raw);
            var unknown = patch.keySet().stream().filter(field -> !FIELDS.contains(field)).toList();
            if (!unknown.isEmpty()) {
                throw error("PATCH_FIELDS_INVALID", "unknown patch fields " + unknown, path, includeId);
            }
            var id = optionalText(patch.get("id"), "id", path, includeId);
            var prefix = "patch " + (position + 1) + ": ";
            if (patch.containsKey("insert")) {
                var inserted = entries(patch.get("insert"), path, includeId);
                if (id == null) {
                    result.addAll(inserted);
                } else {
                    var target = index.get(id);
                    if (target == null) {
                        warn(warnings, "PATCH_TARGET_MISSING", prefix + "insert target not found: " + id,
                            path, includeId);
                        continue;
                    }
                    if (!Boolean.TRUE.equals(target.get("group"))) {
                        warn(warnings, "PATCH_TARGET_NOT_GROUP", prefix + "insert target is not a group: " + id,
                            path, includeId);
                        continue;
                    }
                    if (!(target.get("entries") instanceof List<?>)) {
                        target.put("entries", new ArrayList<>());
                    }
                    @SuppressWarnings("unchecked")
                    var children = (List<Map<String, Object>>) target.get("entries");
                    children.addAll(inserted);
                }
                index(inserted, index);
                continue;
            }
            if (id == null) {
                warn(warnings, "PATCH_ID_MISSING", prefix + "id is required for an override", path, includeId);
                continue;
            }
            var target = index.get(id);
            if (target == null) {
                warn(warnings, "PATCH_TARGET_MISSING", prefix + "target not found: " + id, path, includeId);
                continue;
            }
            var plugin = optionalText(patch.get("plugin"), "plugin", path, includeId);
            if (plugin != null && !plugin.equals(target.get("plugin"))) {
                warn(warnings, "PATCH_NAME_MISMATCH", prefix + "plugin mismatch for " + id
                    + " (expected " + target.get("plugin") + ", got " + plugin + ")", path, includeId);
                continue;
            }
            patch.forEach((field, value) -> {
                if (!field.equals("id") && !field.equals("plugin")) {
                    target.put(field, LiteralValues.mutable(value));
                }
            });
        }
        return LiteralValues.freezeEntries(result);
    }

    private static void index(List<Map<String, Object>> entries,
                               Map<String, Map<String, Object>> index) {
        for (var entry : entries) {
            if (entry.get("id") instanceof String id) {
                index.put(id, entry);
            }
            if (Boolean.TRUE.equals(entry.get("group"))
                && entry.get("entries") instanceof List<?> rawChildren) {
                for (var child : rawChildren) {
                    if (child instanceof Map<?, ?>) {
                        index(List.of(castMutableMap(child)), index);
                    }
                }
            }
        }
    }

    private static List<Map<String, Object>> entries(Object value, Path path, String entryId) {
        if (!(value instanceof List<?> values)) {
            throw error("PATCH_FIELD_INVALID", "insert must be an entry array", path, entryId);
        }
        var result = new ArrayList<Map<String, Object>>(values.size());
        for (var entry : values) {
            if (!(entry instanceof Map<?, ?>)) {
                throw error("PATCH_FIELD_INVALID", "insert entries must be objects", path, entryId);
            }
            result.add(castMutableMap(LiteralValues.mutable(entry)));
        }
        return result;
    }

    private static String optionalText(Object value, String field, Path path, String entryId) {
        if (value == null || "".equals(value)) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw error("PATCH_FIELD_INVALID", field + " must be a non-blank string",
                path, entryId);
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMutableMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static ConfigException error(String code, String message,
                                         Path source, String entryId) {
        return new ConfigException(new ConfigDiagnostic(
            ConfigStage.RESOLVE, code, message, source, entryId), null);
    }

    private static void warn(Consumer<ConfigDiagnostic> warnings, String code, String message,
                              Path source, String entryId) {
        warnings.accept(new ConfigDiagnostic(ConfigStage.RESOLVE, code, message, source, entryId));
    }
}
