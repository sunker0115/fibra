package com.sstlfsj.fibra.loader.pf4j;

import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import org.pf4j.DefaultVersionManager;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

final class PluginGraphPreflight {
    private static final DefaultVersionManager VERSIONS = new DefaultVersionManager();
    private final Function<Path, FibraDirectoryPluginManager> managerFactory;

    PluginGraphPreflight() {
        this(FibraDirectoryPluginManager::new);
    }

    PluginGraphPreflight(Function<Path, FibraDirectoryPluginManager> managerFactory) {
        this.managerFactory = Objects.requireNonNull(managerFactory, "managerFactory");
    }

    Result validate(List<InspectedPluginPackage> current,
                    List<InspectedPluginPackage> candidates) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(candidates, "candidates");
        var candidateIds = uniqueCandidates(candidates);
        var currentById = uniquePackages(current, "current plugin graph contains duplicate id");
        var prospectiveById = new TreeMap<>(currentById);
        for (var candidate : candidates) {
            prospectiveById.put(candidate.descriptor().getPluginId(), candidate);
        }

        validateTemporaryGraph(prospectiveById, candidates);

        var oldAffected = affected(currentById, candidateIds, true);
        var prospectiveAffected = affected(prospectiveById, candidateIds, false);
        var affected = new LinkedHashSet<String>();
        affected.addAll(candidateIds);
        affected.addAll(oldAffected);
        affected.addAll(prospectiveAffected);
        var executableIds = prospectiveById.values().stream()
            .filter(plugin -> !plugin.entrypointClassNames().isEmpty())
            .map(plugin -> plugin.descriptor().getPluginId())
            .sorted()
            .toList();
        return new Result(
            candidates,
            sorted(oldAffected),
            sorted(prospectiveAffected),
            sorted(affected),
            executableIds
        );
    }

    private static List<String> uniqueCandidates(List<InspectedPluginPackage> candidates) {
        var ids = new LinkedHashSet<String>();
        for (var candidate : candidates) {
            var id = candidate.descriptor().getPluginId();
            if (!ids.add(id)) {
                throw failure(FibraArtifactErrorStage.VALIDATE, candidates,
                    "candidate batch contains duplicate plugin id " + id, null);
            }
        }
        return List.copyOf(ids);
    }

    private static Map<String, InspectedPluginPackage> uniquePackages(
        List<InspectedPluginPackage> packages, String message) {
        var byId = new TreeMap<String, InspectedPluginPackage>();
        for (var plugin : packages) {
            var id = plugin.descriptor().getPluginId();
            if (byId.putIfAbsent(id, plugin) != null) {
                throw failure(FibraArtifactErrorStage.VALIDATE, packages,
                    message + " " + id, null);
            }
        }
        return byId;
    }

    private void validateTemporaryGraph(
        Map<String, InspectedPluginPackage> prospectiveById,
        List<InspectedPluginPackage> candidates) {
        var manager = managerFactory.apply(Path.of("."));
        RuntimeException failure = null;
        try {
            manager.loadPluginsStrict(prospectiveById.values().stream()
                .map(InspectedPluginPackage::packageRoot)
                .toList());
            validateOptionalRanges(prospectiveById, candidates);
            validateEntrypoints(manager, prospectiveById.values(), candidates);
            manager.startPlugins();
        } catch (FibraArtifactException exception) {
            failure = exception;
            throw exception;
        } catch (RuntimeException exception) {
            var wrapped = failure(FibraArtifactErrorStage.RESOLVE, candidates,
                "prospective plugin graph cannot be resolved", exception);
            failure = wrapped;
            throw wrapped;
        } finally {
            try {
                manager.unloadPlugins();
            } catch (RuntimeException cleanupFailure) {
                if (failure != null) {
                    failure.addSuppressed(cleanupFailure);
                } else {
                    throw failure(FibraArtifactErrorStage.VALIDATE, candidates,
                        "cannot close prospective plugin graph", cleanupFailure);
                }
            }
        }
    }

    private static void validateOptionalRanges(
        Map<String, InspectedPluginPackage> prospectiveById,
        List<InspectedPluginPackage> candidates) {
        for (var plugin : prospectiveById.values()) {
            for (var dependency : plugin.descriptor().getDependencies()) {
                if (!dependency.isOptional()) {
                    continue;
                }
                var target = prospectiveById.get(dependency.getPluginId());
                if (target == null) {
                    continue;
                }
                try {
                    if (!VERSIONS.checkVersionConstraint(target.descriptor().getVersion(),
                        dependency.getPluginVersionSupport())) {
                        throw failure(FibraArtifactErrorStage.RESOLVE, candidates,
                            "optional dependency " + dependency.getPluginId()
                                + " does not satisfy " + dependency.getPluginVersionSupport(),
                            null);
                    }
                } catch (FibraArtifactException exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    throw failure(FibraArtifactErrorStage.RESOLVE, candidates,
                        "optional dependency range is invalid for "
                            + dependency.getPluginId(), exception);
                }
            }
        }
    }

    private static void validateEntrypoints(
        FibraDirectoryPluginManager manager,
        Collection<InspectedPluginPackage> packages,
        List<InspectedPluginPackage> candidates) {
        for (var plugin : packages) {
            var classNames = plugin.entrypointClassNames();
            if (classNames.size() > 1) {
                throw failure(FibraArtifactErrorStage.VALIDATE, candidates,
                    "plugin " + plugin.descriptor().getPluginId()
                        + " declares more than one Fibra entrypoint", null);
            }
            if (classNames.isEmpty()) {
                continue;
            }
            var classLoader = manager.getPluginClassLoader(plugin.descriptor().getPluginId());
            var className = classNames.getFirst();
            final Class<?> type;
            try {
                type = Class.forName(className, false, classLoader);
            } catch (ClassNotFoundException | LinkageError exception) {
                throw failure(FibraArtifactErrorStage.VALIDATE, candidates,
                    "cannot link Fibra entrypoint " + className, exception);
            }
            if (type.getClassLoader() != classLoader) {
                throw failure(FibraArtifactErrorStage.VALIDATE, candidates,
                    "Fibra entrypoint must be defined by its plugin ClassLoader " + className,
                    null);
            }
            if (!FibraPluginEntrypoint.class.isAssignableFrom(type)
                || !Modifier.isPublic(type.getModifiers())
                || type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
                throw failure(FibraArtifactErrorStage.VALIDATE, candidates,
                    "indexed type is not a concrete Fibra entrypoint " + className, null);
            }
            try {
                type.getConstructor();
            } catch (NoSuchMethodException | SecurityException exception) {
                throw failure(FibraArtifactErrorStage.VALIDATE, candidates,
                    "Fibra entrypoint must have a public no-argument constructor " + className,
                    exception);
            }
        }
    }

    private static Set<String> affected(Map<String, InspectedPluginPackage> graph,
                                        List<String> candidateIds,
                                        boolean installedOnly) {
        var dependents = new LinkedHashMap<String, Set<String>>();
        graph.keySet().forEach(id -> dependents.put(id, new LinkedHashSet<>()));
        for (var plugin : graph.values()) {
            var dependentId = plugin.descriptor().getPluginId();
            for (var dependency : plugin.descriptor().getDependencies()) {
                var targetDependents = dependents.get(dependency.getPluginId());
                if (targetDependents != null) {
                    targetDependents.add(dependentId);
                }
            }
        }
        var result = new LinkedHashSet<String>();
        var queue = new ArrayDeque<String>();
        for (var candidateId : candidateIds) {
            if (!installedOnly || graph.containsKey(candidateId)) {
                if (result.add(candidateId)) {
                    queue.add(candidateId);
                }
            }
        }
        while (!queue.isEmpty()) {
            for (var dependent : dependents.getOrDefault(queue.remove(), Set.of())) {
                if (result.add(dependent)) {
                    queue.add(dependent);
                }
            }
        }
        return result;
    }

    private static List<String> sorted(Collection<String> values) {
        return values.stream().sorted().toList();
    }

    private static FibraArtifactException failure(FibraArtifactErrorStage stage,
                                                  List<InspectedPluginPackage> packages,
                                                  String message,
                                                  Throwable cause) {
        return new FibraArtifactException(
            stage,
            packages.stream().map(InspectedPluginPackage::packageRoot).toList(),
            packages.stream().map(plugin -> plugin.descriptor().getPluginId()).distinct().toList(),
            message,
            cause
        );
    }

    record Result(
        List<InspectedPluginPackage> candidates,
        List<String> oldAffectedArtifactIds,
        List<String> prospectiveAffectedArtifactIds,
        List<String> affectedArtifactIds,
        List<String> executableArtifactIds
    ) {
        Result {
            candidates = List.copyOf(candidates);
            oldAffectedArtifactIds = List.copyOf(oldAffectedArtifactIds);
            prospectiveAffectedArtifactIds = List.copyOf(prospectiveAffectedArtifactIds);
            affectedArtifactIds = List.copyOf(affectedArtifactIds);
            executableArtifactIds = List.copyOf(executableArtifactIds);
        }

        List<String> candidateArtifactIds() {
            return candidates.stream()
                .map(candidate -> candidate.descriptor().getPluginId())
                .toList();
        }
    }
}
