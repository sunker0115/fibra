package com.sstlfsj.fibra.loader.config;

import java.util.List;
import java.util.Map;

/** 按顺序应用到一份配置文件的不可变 patch。 */
public final class FibraConfigPatch {
    public enum Operation {
        INSERT,
        OVERRIDE
    }

    private final Operation operation;
    private final String targetId;
    private final String expectedPluginId;
    private final List<Map<String, Object>> entries;
    private final Map<String, Object> fields;

    private FibraConfigPatch(Operation operation, String targetId, String expectedPluginId,
                             List<Map<String, Object>> entries, Map<String, Object> fields) {
        this.operation = operation;
        this.targetId = targetId;
        this.expectedPluginId = expectedPluginId;
        this.entries = entries;
        this.fields = fields;
    }

    public static FibraConfigPatch insert(Map<String, ?> entry) {
        return insert(null, List.of(entry));
    }

    public static FibraConfigPatch insert(String groupId, Map<String, ?> entry) {
        return insert(groupId, List.of(entry));
    }

    public static FibraConfigPatch insert(String groupId,
                                          List<? extends Map<String, ?>> entries) {
        if (groupId != null) {
            requireText(groupId, "groupId");
        }
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("insert entries must not be empty");
        }
        return new FibraConfigPatch(Operation.INSERT, groupId, null,
            LiteralValues.freezeEntries(entries), Map.of());
    }

    public static FibraConfigPatch override(String entryId, String expectedPluginId,
                                            Map<String, ?> fields) {
        requireText(entryId, "entryId");
        if (expectedPluginId != null) {
            requireText(expectedPluginId, "expectedPluginId");
        }
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("override fields must not be empty");
        }
        if (fields.containsKey("id") || fields.containsKey("insert")
            || fields.containsKey("name")) {
            throw new IllegalArgumentException(
                "override fields must not contain id, name or insert");
        }
        return new FibraConfigPatch(Operation.OVERRIDE, entryId, expectedPluginId,
            List.of(), LiteralValues.freezeMap(fields));
    }

    public Operation operation() {
        return operation;
    }

    public String targetId() {
        return targetId;
    }

    public String expectedPluginId() {
        return expectedPluginId;
    }

    public List<Map<String, Object>> entries() {
        return entries;
    }

    public Map<String, Object> fields() {
        return fields;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
