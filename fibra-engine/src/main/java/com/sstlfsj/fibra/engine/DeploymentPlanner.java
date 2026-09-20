package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.*;
import com.sstlfsj.fibra.config.*;
import java.util.*;

/** Engine 独占的 entry 展开与 plan 校验；runtime 返回的公共计划不能改写全局 wiring。 */
final class DeploymentPlanner {
    record Input(DeploymentTargetCompiler.Compilation facets, DesiredEvaluation desired,
                 Map<ExecutionUnitKey, ExecutionUnitPlan> units,
                 Map<String, DesiredInputEntry> entries, List<ExecutionUnitKey> order) { }

    Input inputs(DeploymentTarget target, Collection<ManagedPluginPackage> packages,
                 Collection<BuiltInPluginPackage> builtIns,
                 HostCapabilitySnapshot capabilities) {
        var facets = new DeploymentTargetCompiler().compile(target, packages, builtIns);
        var availableCapabilities = Objects.requireNonNull(capabilities,
            "capabilities").availableNames();
        var desired = DesiredEvaluation.evaluate(target.desiredGraph(), target.configContext());
        var entries = new LinkedHashMap<String, DesiredInputEntry>();
        var artifacts = new LinkedHashMap<String, ArtifactId>();
        var units = new LinkedHashMap<ExecutionUnitKey, ExecutionUnitPlan>();
        desired.entries().forEach((id, resolved) -> {
            if (!resolved.effective().enabled() || !(resolved.input() instanceof DesiredInputEntry entry)) return;
            var ref = entry.definitionRef();
            var selection = target.selections().get(new PluginId(ref.pluginId()));
            if (selection == null) {
                throw new IllegalArgumentException("active desired entry references an unselected package: " + id);
            }
            if (!selection.enabled()) return;
            entries.put(id, entry);
            var builtIn = facets.builtInFacets().values().stream().filter(value ->
                value.pluginPackage().pluginId().value().equals(ref.pluginId()) && value.facet().facetId().value().equals(ref.facetId()))
                .findFirst();
            if (builtIn.isPresent()) {
                var value = builtIn.get();
                if (!value.facet().definitionIds().contains(ref.definitionId())) {
                    throw new IllegalArgumentException("unknown built-in definition " + ref);
                }
                artifacts.put(id, value.artifactId());
            } else {
                var facet = facets.facets().values().stream().map(DeploymentTargetCompiler.CompiledFacet::facet)
                    .filter(value -> value.pluginId().value().equals(ref.pluginId())
                        && value.facet().facetId().value().equals(ref.facetId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown desired facet " + ref));
                artifacts.put(id, facet.artifactId());
            }
        });
        entries.forEach((id, entry) -> {
            var artifact = artifacts.get(id);
            var dynamic = facets.facets().get(artifact);
            var builtIn = facets.builtInFacets().get(artifact);
            var requiredCapabilities = requiredCapabilities(artifact, facets,
                new HashSet<>());
            if (!availableCapabilities.containsAll(requiredCapabilities)) {
                var missing = new TreeSet<>(requiredCapabilities);
                missing.removeAll(availableCapabilities);
                throw new IllegalArgumentException(
                    "active execution unit requires unavailable capabilities "
                        + missing + ": " + id);
            }
            var dependencies = (dynamic == null ? builtIn.dependencies() : dynamic.dependencies())
                .stream().flatMap(dependency -> artifacts.entrySet().stream()
                    .filter(candidate -> candidate.getValue().equals(dependency.artifactId()))
                    .map(candidate -> new ExecutionUnitKey(candidate.getKey()))).sorted().distinct().toList();
            var runtime = dynamic == null ? builtIn.facet().runtimeId() : dynamic.facet().facet().runtimeId();
            var placement = dynamic == null ? builtIn.facet().executionTarget() : dynamic.facet().facet().executionTarget();
            var ref = entry.definitionRef();
            var selection = target.selections().get(new PluginId(ref.pluginId()));
            var key = new ExecutionUnitKey(id);
            units.put(key, ExecutionUnitPlan.builder(key, runtime, placement).artifactId(artifact)
                .provenance(ref.pluginId(), ref.facetId(), selection.packageRevision())
                .dependencies(dependencies).build());
        });
        return new Input(facets, desired, Collections.unmodifiableMap(units),
            Collections.unmodifiableMap(entries), order(units));
    }

    Set<ExecutionUnitKey> affected(Input next, CompiledDeployment previous,
                                   Set<ExecutionUnitKey> forced, boolean all) {
        var changed = new LinkedHashSet<>(forced);
        if (previous == null || all) {
            changed.addAll(next.units().keySet());
            if (previous != null) changed.addAll(previous.dependencyFirst());
            return changed;
        }
        var old = units(previous);
        var keys = new LinkedHashSet<>(old.keySet());
        keys.addAll(next.units().keySet());
        for (var key : keys) {
            var prior = old.get(key);
            var target = next.units().get(key);
            var before = previous.desired().entries().get(key.value());
            var after = next.desired().entries().get(key.value());
            if (prior == null || target == null || !samePlan(prior, target)
                || !staticFacetInputs(prior, previous.facetGraph()).equals(
                    staticFacetInputs(target, next.facets()))
                || !before.input().equals(after.input())
                || !before.effective().equals(after.effective())
                || !before.resolvedConfig().equals(after.resolvedConfig())) changed.add(key);
        }
        boolean expanded;
        do {
            expanded = addDependents(changed, old) | addDependents(changed, next.units());
        } while (expanded);
        return changed;
    }

    CompiledDeployment validate(Input input, String fingerprint,
                                Map<RuntimeId, RuntimePlan> plans, Set<ExecutionUnitKey> retained) {
        var units = new LinkedHashMap<ExecutionUnitKey, ExecutionUnitPlan>();
        var bindings = new LinkedHashMap<String, DefinitionBindingPlan>();
        plans.forEach((runtime, plan) -> {
            if (!runtime.equals(plan.runtimeId())) throw new IllegalArgumentException("runtime plan identity mismatch");
            plan.units().forEach((key, unit) -> {
                var expected = input.units().get(key);
                if (expected == null || !samePlan(expected, unit) || units.putIfAbsent(key, unit) != null) {
                    throw new IllegalArgumentException("runtime plan changes global unit identity/wiring: " + key);
                }
            });
            for (var binding : plan.definitions()) {
                var entry = input.entries().get(binding.desiredEntryId());
                if (entry == null || !entry.definitionRef().equals(binding.definition())
                    || !binding.unitKey().value().equals(binding.desiredEntryId())
                    || entry.publicationRequirement() != binding.publicationRequirement()
                    || bindings.putIfAbsent(binding.desiredEntryId(), binding) != null) {
                    throw new IllegalArgumentException("invalid definition binding " + binding.desiredEntryId());
                }
            }
        });
        if (!units.keySet().equals(input.units().keySet()) || !bindings.keySet().equals(input.entries().keySet())) {
            throw new IllegalArgumentException("definition/unit coverage differs from active desired entries");
        }
        var affected = new LinkedHashSet<>(units.keySet());
        affected.removeAll(retained);
        return CompiledDeployment.builder(input.facets().target(), fingerprint)
            .facetGraph(input.facets()).desired(input.desired()).runtimePlans(plans)
            .dependencyFirst(input.order()).affectedUnits(affected).retainedUnits(retained).build();
    }

    static Map<ExecutionUnitKey, ExecutionUnitPlan> units(CompiledDeployment compiled) {
        var result = new LinkedHashMap<ExecutionUnitKey, ExecutionUnitPlan>();
        compiled.runtimePlans().values().forEach(plan -> result.putAll(plan.units()));
        return result;
    }
    static boolean samePlan(ExecutionUnitPlan left, ExecutionUnitPlan right) {
        return left.key().equals(right.key()) && left.runtimeId().equals(right.runtimeId())
            && left.executionTarget().equals(right.executionTarget()) && left.artifactId().equals(right.artifactId())
            && left.pluginId().equals(right.pluginId()) && left.facetId().equals(right.facetId())
            && left.packageRevision().equals(right.packageRevision())
            && new HashSet<>(left.dependencies()).equals(new HashSet<>(right.dependencies()));
    }
    private record StaticFacetInputEdge(
        ArtifactId ownerArtifactId,
        ResolvedFacetDependency dependency) { }

    private static Set<StaticFacetInputEdge> staticFacetInputs(
            ExecutionUnitPlan unit, DeploymentTargetCompiler.Compilation facets) {
        var result = new LinkedHashSet<StaticFacetInputEdge>();
        collectStaticFacetInputs(unit.artifactId(), facets,
            new HashSet<>(), result);
        return result;
    }
    private static Set<String> requiredCapabilities(
            ArtifactId artifact, DeploymentTargetCompiler.Compilation facets,
            Set<ArtifactId> visited) {
        if (!visited.add(artifact)) return Set.of();
        var dynamic = facets.facets().get(artifact);
        var builtIn = facets.builtInFacets().get(artifact);
        if (dynamic == null && builtIn == null) {
            throw new IllegalArgumentException(
                "execution unit references an unknown facet artifact " + artifact);
        }
        var required = new LinkedHashSet<String>(dynamic == null
            ? builtIn.facet().requiredCapabilities()
            : dynamic.facet().facet().requiredCapabilities());
        var dependencies = dynamic == null
            ? builtIn.dependencies() : dynamic.dependencies();
        dependencies.forEach(dependency -> required.addAll(
            requiredCapabilities(dependency.artifactId(), facets, visited)));
        return Set.copyOf(required);
    }
    private static void collectStaticFacetInputs(
            ArtifactId artifact, DeploymentTargetCompiler.Compilation facets,
            Set<ArtifactId> visited,
            Set<StaticFacetInputEdge> result) {
        if (!visited.add(artifact)) return;
        var dynamic = facets.facets().get(artifact);
        var builtIn = facets.builtInFacets().get(artifact);
        if (dynamic == null && builtIn == null) {
            throw new IllegalArgumentException(
                "execution unit references an unknown facet artifact " + artifact);
        }
        var dependencies = dynamic == null
            ? builtIn.dependencies() : dynamic.dependencies();
        for (var dependency : dependencies) {
            result.add(new StaticFacetInputEdge(artifact, dependency));
            collectStaticFacetInputs(dependency.artifactId(), facets, visited,
                result);
        }
    }
    static Set<ExecutionUnitKey> closure(Set<ExecutionUnitKey> initial, CompiledDeployment compiled) {
        var result = new LinkedHashSet<>(initial);
        var units = units(compiled);
        while (addDependents(result, units)) { }
        return result;
    }
    private static boolean addDependents(Set<ExecutionUnitKey> changed,
                                         Map<ExecutionUnitKey, ExecutionUnitPlan> units) {
        boolean result = false;
        for (var unit : units.values()) {
            if (unit.dependencies().stream().anyMatch(changed::contains)) result |= changed.add(unit.key());
        }
        return result;
    }
    private static List<ExecutionUnitKey> order(Map<ExecutionUnitKey, ExecutionUnitPlan> units) {
        var result = new ArrayList<ExecutionUnitKey>();
        var visiting = new HashSet<ExecutionUnitKey>();
        var visited = new HashSet<ExecutionUnitKey>();
        for (var key : units.keySet()) visit(key, units, visiting, visited, result);
        return List.copyOf(result);
    }
    private static void visit(ExecutionUnitKey key, Map<ExecutionUnitKey, ExecutionUnitPlan> units,
                              Set<ExecutionUnitKey> visiting, Set<ExecutionUnitKey> visited,
                              List<ExecutionUnitKey> order) {
        if (visited.contains(key)) return;
        if (!visiting.add(key)) throw new IllegalArgumentException("execution dependency cycle at " + key);
        var unit = units.get(key);
        if (unit == null) throw new IllegalArgumentException("unknown execution dependency " + key);
        unit.dependencies().forEach(dependency -> visit(dependency, units, visiting, visited, order));
        visiting.remove(key);
        visited.add(key);
        order.add(key);
    }
}
