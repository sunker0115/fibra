package com.sstlfsj.fibra.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ConfigPatchApplier {
    List<Map<String, Object>> apply(List<Map<String, Object>> source, Object rawPatches,
                                    Path path, String includeId) {
        if (rawPatches == null) {
            return source;
        }
        if (!(rawPatches instanceof List<?> patches)) {
            throw error("PATCHES_NOT_ARRAY", "patches must be an array", path, includeId);
        }
        @SuppressWarnings("unchecked")
        var result = (List<Map<String, Object>>) (List<?>) LiteralValues.mutable(source);
        for (var item : patches) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw error("PATCH_NOT_OBJECT", "every patch must be an object", path, includeId);
            }
            var patch = LiteralValues.freezeMap(raw);
            var override = patch.containsKey("target");
            var insert = patch.containsKey("insert");
            if (override == insert) {
                throw error("PATCH_KIND_INVALID",
                    "patch must declare exactly one of target or insert", path, includeId);
            }
            if (override) {
                requireFields(patch, List.of("target", "set"), path, includeId);
                var target = text(patch.get("target"), "target", path, includeId);
                var fields = object(patch.get("set"), "set", path, includeId);
                if (fields.isEmpty()) {
                    throw error("PATCH_SET_EMPTY", "patch set must not be empty", path, includeId);
                }
                var node = find(result, target);
                if (node == null) {
                    throw error("PATCH_TARGET_MISSING",
                        "patch target does not exist: " + target, path, includeId);
                }
                fields.forEach((name, value) -> {
                    if (value == null) {
                        node.value().remove(name);
                    } else {
                        node.value().put(name, LiteralValues.mutable(value));
                    }
                });
                continue;
            }

            requireFields(patch, List.of("after", "insert"), path, includeId);
            var after = text(patch.get("after"), "after", path, includeId);
            var value = object(patch.get("insert"), "insert", path, includeId);
            var node = find(result, after);
            if (node == null) {
                throw error("PATCH_TARGET_MISSING",
                    "patch target does not exist: " + after, path, includeId);
            }
            node.container().add(node.index() + 1,
                castMutableMap(LiteralValues.mutable(value)));
        }
        return LiteralValues.freezeEntries(result);
    }

    private static Node find(List<Map<String, Object>> entries, String target) {
        return find(entries, target, "");
    }

    private static Node find(List<Map<String, Object>> entries, String target,
                             String parent) {
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.get(index);
            var rawId = entry.get("id");
            if (!(rawId instanceof String id) || id.isBlank()) {
                continue;
            }
            var fullId = parent.isEmpty() ? id : parent + ':' + id;
            if (fullId.equals(target)) {
                return new Node(entries, index, entry);
            }
            if (Boolean.TRUE.equals(entry.get("group"))
                && entry.get("entries") instanceof List<?> rawChildren) {
                @SuppressWarnings("unchecked")
                var children = (List<Map<String, Object>>) rawChildren;
                var found = find(children, target, fullId);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void requireFields(Map<String, Object> patch, List<String> fields,
                                      Path path, String entryId) {
        if (patch.size() != fields.size() || !patch.keySet().containsAll(fields)) {
            throw error("PATCH_FIELDS_INVALID",
                "patch only accepts fields " + fields, path, entryId);
        }
    }

    private static String text(Object value, String field, Path path, String entryId) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw error("PATCH_FIELD_INVALID", field + " must be a non-blank string",
                path, entryId);
        }
        return text;
    }

    private static Map<String, Object> object(Object value, String field,
                                              Path path, String entryId) {
        if (!(value instanceof Map<?, ?> map)) {
            throw error("PATCH_FIELD_INVALID", field + " must be an object", path, entryId);
        }
        return LiteralValues.freezeMap(map);
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

    private record Node(List<Map<String, Object>> container, int index,
                        Map<String, Object> value) {
    }
}
