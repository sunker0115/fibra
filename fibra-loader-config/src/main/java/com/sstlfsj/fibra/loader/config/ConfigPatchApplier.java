package com.sstlfsj.fibra.loader.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

final class ConfigPatchApplier {
    List<Map<String, Object>> apply(List<? extends Map<String, ?>> source,
                                    List<FibraConfigPatch> patches,
                                    Consumer<FibraConfigWarning> warningSink) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(patches, "patches");
        Objects.requireNonNull(warningSink, "warningSink");
        var result = mutableEntries(source);
        var entries = new LinkedHashMap<String, Map<String, Object>>();
        index(result, "", entries);

        for (var patch : patches) {
            if (patch.operation() == FibraConfigPatch.Operation.INSERT) {
                applyInsert(result, entries, patch, warningSink);
            } else {
                applyOverride(entries, patch, warningSink);
            }
        }
        return LiteralValues.freezeEntries(result);
    }

    private static void applyInsert(List<Map<String, Object>> root,
                                    Map<String, Map<String, Object>> entries,
                                    FibraConfigPatch patch,
                                    Consumer<FibraConfigWarning> warningSink) {
        List<Map<String, Object>> target;
        if (patch.targetId() == null) {
            target = root;
        } else {
            var group = entries.get(patch.targetId());
            if (group == null) {
                warn(warningSink, "PATCH_TARGET_MISSING",
                    "patch insert target does not exist", patch.targetId());
                return;
            }
            if (!Boolean.TRUE.equals(group.get("group"))) {
                warn(warningSink, "PATCH_TARGET_NOT_GROUP",
                    "patch insert target is not a group", patch.targetId());
                return;
            }
            var config = group.get("config");
            if (config == null) {
                target = new ArrayList<>();
                group.put("config", target);
            } else if (config instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                var typed = (List<Map<String, Object>>) list;
                target = typed;
            } else {
                warn(warningSink, "PATCH_TARGET_NOT_GROUP",
                    "patch insert target has non-array group config", patch.targetId());
                return;
            }
        }
        var inserted = mutableEntries(patch.entries());
        target.addAll(inserted);
        index(inserted, patch.targetId() == null ? "" : patch.targetId(), entries);
    }

    private static void applyOverride(Map<String, Map<String, Object>> entries,
                                      FibraConfigPatch patch,
                                      Consumer<FibraConfigWarning> warningSink) {
        var target = entries.get(patch.targetId());
        if (target == null) {
            warn(warningSink, "PATCH_TARGET_MISSING",
                "patch override target does not exist", patch.targetId());
            return;
        }
        if (patch.expectedPluginId() != null
            && !patch.expectedPluginId().equals(target.get("name"))) {
            warn(warningSink, "PATCH_NAME_MISMATCH",
                "patch expected plugin does not match target", patch.targetId());
            return;
        }
        patch.fields().forEach((name, value) -> {
            if (value == null) {
                target.remove(name);
            } else {
                target.put(name, LiteralValues.mutable(value));
            }
        });
    }

    private static void index(List<Map<String, Object>> source, String parentId,
                              Map<String, Map<String, Object>> entries) {
        for (var entry : source) {
            var id = entry.get("id");
            String entryId = null;
            if (id instanceof String name && !name.isBlank()) {
                entryId = parentId.isEmpty() ? name : parentId + ':' + name;
                entries.put(entryId, entry);
            }
            if (Boolean.TRUE.equals(entry.get("group"))
                && entry.get("config") instanceof List<?> children) {
                @SuppressWarnings("unchecked")
                var typed = (List<Map<String, Object>>) children;
                index(typed, entryId == null ? parentId : entryId, entries);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mutableEntries(
        List<? extends Map<String, ?>> source) {
        return (List<Map<String, Object>>) (List<?>) LiteralValues.mutable(source);
    }

    private static void warn(Consumer<FibraConfigWarning> warningSink, String code,
                             String message, String entryId) {
        warningSink.accept(new FibraConfigWarning(code, message, entryId));
    }
}
