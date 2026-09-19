package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.RuntimeId;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RuntimePlan {
    private final RuntimeId runtimeId;
    private final Map<ExecutionUnitKey, ExecutionUnitPlan> units;
    private final List<DefinitionBindingPlan> definitions;

    private RuntimePlan(RuntimeId runtimeId,
                        Collection<ExecutionUnitPlan> units,
                        Collection<DefinitionBindingPlan> definitions) {
        this.runtimeId = Objects.requireNonNull(runtimeId, "runtimeId");
        var unitIndex = new LinkedHashMap<ExecutionUnitKey, ExecutionUnitPlan>();
        Objects.requireNonNull(units, "units").stream()
            .map(value -> Objects.requireNonNull(value, "unit"))
            .sorted(Comparator.comparing(ExecutionUnitPlan::key))
            .forEach(unit -> {
                if (!runtimeId.equals(unit.runtimeId())) {
                    throw new IllegalArgumentException(
                        "execution unit belongs to another runtime");
                }
                if (unitIndex.putIfAbsent(unit.key(), unit) != null) {
                    throw new IllegalArgumentException(
                        "duplicate execution unit " + unit.key());
                }
            });
        this.units = Collections.unmodifiableMap(unitIndex);
        this.definitions = Objects.requireNonNull(definitions, "definitions")
            .stream().map(value -> Objects.requireNonNull(value, "definition"))
            .sorted(Comparator.comparing(DefinitionBindingPlan::desiredEntryId))
            .toList();
        if (this.definitions.stream().map(DefinitionBindingPlan::desiredEntryId)
            .distinct().count() != this.definitions.size()) {
            throw new IllegalArgumentException("duplicate desired definition binding");
        }
        if (this.definitions.stream().anyMatch(binding ->
            !this.units.containsKey(binding.unitKey()))) {
            throw new IllegalArgumentException(
                "definition binding references an unknown execution unit");
        }
        if (this.definitions.size() != this.units.size()
            || this.definitions.stream().map(DefinitionBindingPlan::unitKey).distinct().count() != this.units.size()
            || this.definitions.stream().anyMatch(binding ->
                !binding.unitKey().value().equals(binding.desiredEntryId()))) {
            throw new IllegalArgumentException("each runtime unit requires exactly one binding for its desired entry id");
        }
    }

    public static RuntimePlan of(RuntimeId runtimeId,
                                 Collection<ExecutionUnitPlan> units,
                                 Collection<DefinitionBindingPlan> definitions) {
        return new RuntimePlan(runtimeId, units, definitions);
    }

    public RuntimeId runtimeId() { return runtimeId; }
    public Map<ExecutionUnitKey, ExecutionUnitPlan> units() { return units; }
    public List<DefinitionBindingPlan> definitions() { return definitions; }
}
