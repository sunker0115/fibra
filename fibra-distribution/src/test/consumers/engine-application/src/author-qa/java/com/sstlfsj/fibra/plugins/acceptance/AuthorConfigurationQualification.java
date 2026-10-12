package com.sstlfsj.fibra.plugins.acceptance;

import com.sstlfsj.fibra.artifact.PluginConfigurationResource;
import com.sstlfsj.fibra.artifact.PluginPackageRecord;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.config.ConfigSchema;
import com.sstlfsj.fibra.config.ConfigSchemaSource;
import com.sstlfsj.fibra.config.DefinitionConfigDocument;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.deployment.DeploymentTargetStore;
import com.sstlfsj.fibra.engine.deployment.PackageDefinitionCatalog;
import com.sstlfsj.fibra.engine.deployment.PluginSelection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Consumer;

/** Test/author QA owner: retains the Engine ownership scope on cleanup failure under the fail-stop policy. */
public final class AuthorConfigurationQualification implements AutoCloseable {
    final FibraEngine engine;
    final PluginPackageStore packages;
    final DeploymentTargetStore targets;
    PluginConfigurationResource pending;
    Throwable readFailure;
    Throwable closeFailure;
    Throwable terminationFailure;
    boolean closed;
    private final Function<PluginPackageRecord, PluginConfigurationResource> resources;
    private final Consumer<AuthorConfigurationQualification> terminate;

    public AuthorConfigurationQualification(FibraEngine engine, PluginPackageStore packages,
                                    DeploymentTargetStore targets, Consumer<AuthorConfigurationQualification> terminate) {
        this(engine, packages, targets, PluginPackageRecord::configurationResource, terminate);
    }

    public AuthorConfigurationQualification(FibraEngine engine, PluginPackageStore packages,
                                    DeploymentTargetStore targets,
                                    Function<PluginPackageRecord, PluginConfigurationResource> resources,
                                    Consumer<AuthorConfigurationQualification> terminate) {
        this.engine = engine;
        this.packages = packages;
        this.targets = targets;
        this.resources = resources;
        this.terminate = terminate;
    }

    public DefinitionConfigDocument qualify(PluginSelection selection, long targetRevision) {
        if (pending != null || closed) throw new IllegalStateException("QA owner unavailable");
        var catalog = engine.describeDefinitions(selection.pluginId(), targetRevision)
            .block(Duration.ofSeconds(20));
        if (!catalog.pluginId().equals(selection.pluginId())
            || !catalog.packageRevision().equals(selection.packageRevision())
            || catalog.targetRevision() != targetRevision) {
            throw new IllegalStateException("catalog identity mismatch");
        }
        var record = packages.find(selection.pluginId(), selection.packageRevision()).orElseThrow();
        pending = resources.apply(record);
        byte[] bytes = null;
        readFailure = null;
        try { bytes = pending.read(); } catch (Throwable failure) { readFailure = failure; }
        try {
            pending.close();
            pending = null;
        } catch (Throwable failure) {
            closeFailure = failure;
            // The test host must retain this owner before making its termination notification.
            try { terminate.accept(this); } catch (Throwable notificationFailure) {
                terminationFailure = notificationFailure;
            }
            rethrowFatal(closeFailure);
            rethrowFatal(readFailure);
            rethrowFatal(terminationFailure);
            throw new IllegalStateException("QA configuration cleanup failed");
        }
        rethrowFatal(readFailure);
        if (readFailure != null) throw new IllegalStateException("QA configuration read failed");
        var document = DefinitionConfigDocument.parse(bytes);
        check(catalog, document);
        return document;
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
    }

    public static void check(PackageDefinitionCatalog catalog, DefinitionConfigDocument document) {
        var names = new LinkedHashMap<String, Set<String>>();
        catalog.facets().forEach(facet -> names.put(facet.facetId().value(), Set.copyOf(facet.definitionIds())));
        if (!document.facets().keySet().equals(names.keySet())) {
            throw new IllegalStateException("configuration facet set mismatch");
        }
        var sources = new ArrayList<ConfigSchemaSource>();
        names.forEach((facet, definitions) -> {
            var schemas = document.facets().get(facet);
            if (!schemas.keySet().equals(definitions)) {
                throw new IllegalStateException("configuration definition set mismatch");
            }
            schemas.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> sources.add(entry.getValue()));
        });
        if (ConfigSchema.checkSources(sources).isPresent()) {
            throw new IllegalStateException("configuration schema qualification failed");
        }
    }

    @Override
    public void close() {
        if (pending != null) throw new IllegalStateException("QA retains failed resource and engine dependencies");
        if (!closed) {
            engine.close();
            closed = true;
        }
    }
}
