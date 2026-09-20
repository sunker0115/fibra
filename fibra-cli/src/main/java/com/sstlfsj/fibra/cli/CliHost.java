package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.PluginPackageInstallTransaction;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.bridge.ContributionKindRegistry;
import com.sstlfsj.fibra.config.ConfigLimits;
import com.sstlfsj.fibra.config.FileDesiredStateRepository;
import com.sstlfsj.fibra.engine.FileDeploymentTargetStore;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.HostTerminationPort;
import com.sstlfsj.fibra.engine.PluginSelection;
import com.sstlfsj.fibra.engine.PublishedRuntime;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.registry.FilePluginAuditRepository;
import com.sstlfsj.fibra.registry.PluginDeploymentRequest;
import com.sstlfsj.fibra.registry.PluginRegistry;
import com.sstlfsj.fibra.registry.RegistrySnapshot;
import com.sstlfsj.fibra.runtime.java.JavaRuntimeProvider;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeOptions;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeProvider;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** CLI profile namespace 内唯一的 Engine、Registry 与持久资源所有者。 */
final class CliHost implements AutoCloseable {
    private final CliPaths paths;
    private final PluginPackageStore packages;
    private final FileDeploymentTargetStore targets;
    private final FibraEngine engine;
    private final PluginRegistry registry;
    private final FilePluginAuditRepository audit;
    private final ExecutorService terminationExecutor;
    private boolean closed;
    private Throwable closeFailure;

    private CliHost(CliPaths paths, PluginPackageStore packages, FileDeploymentTargetStore targets,
                    FibraEngine engine, PluginRegistry registry,
                    FilePluginAuditRepository audit, ExecutorService terminationExecutor) {
        this.paths = paths;
        this.packages = packages;
        this.targets = targets;
        this.engine = engine;
        this.registry = registry;
        this.audit = audit;
        this.terminationExecutor = terminationExecutor;
    }

    static CliHost open(CliPaths paths) {
        PluginPackageStore packages = null;
        FileDeploymentTargetStore targets = null;
        FilePluginAuditRepository audit = null;
        ExecutorService termination = null;
        FibraEngine engine = null;
        try {
            Files.createDirectories(paths.workspaceRoot());
            Files.createDirectories(paths.storageRoot());
            packages = new PluginPackageStore(paths.packageStoreRoot());
            targets = new FileDeploymentTargetStore(paths.stateRoot());
            audit = new FilePluginAuditRepository(paths.auditFile());
            var terminationExecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon()
                .name("fibra-cli-termination-", 0).factory());
            termination = terminationExecutor;
            var owner = new AtomicReference<CliHost>();
            HostTerminationPort terminationPort = request -> terminationExecutor.execute(() -> {
                var host = owner.get();
                if (host != null) host.close();
            });
            engine = FibraEngine.builder(packages, targets)
                .contributionKinds(ContributionKindRegistry.of(ToolContributions.KIND))
                .hostTerminationPort(terminationPort)
                .runtimeProvider(new JavaRuntimeProvider(List.of()))
                .runtimeProvider(new NodeRuntimeProvider(NodeRuntimeOptions.defaults(
                    paths.nodeExecutable(), paths.nodeSessionRoot())))
                .build();
            var registry = new PluginRegistry(engine, packages, audit);
            engine.startAsync().block();
            var host = new CliHost(paths, packages, targets, engine, registry, audit, termination);
            owner.set(host);
            if (registry.snapshot().target().isEmpty()) host.apply();
            return host;
        } catch (Throwable failure) {
            close(failure, engine);
            close(failure, audit);
            close(failure, targets);
            close(failure, packages);
            if (termination != null) termination.shutdownNow();
            throwUnchecked(failure);
            throw new AssertionError("unreachable");
        }
    }

    PluginRegistry registry() { return registry; }
    PublishedRuntime published() { return engine.published(); }

    RegistrySnapshot apply() {
        var graph = new FileDesiredStateRepository(paths.profileFile(),
            ConfigLimits.defaults()).load().graph();
        var selected = new ProfilePackageSource(paths.profilePackagesFile(), paths.pluginsRoot()).load();
        var transactions = new ArrayList<PluginPackageInstallTransaction>();
        final RegistrySnapshot result;
        try {
            var prepared = new LinkedHashMap<PluginId,
                PluginPackageInstallTransaction>();
            for (var source : selected) {
                var transaction = packages.prepareInstall(source);
                transactions.add(transaction);
                var pluginId = transaction.candidate().pluginId();
                if (prepared.putIfAbsent(pluginId, transaction) != null) {
                    throw new IllegalArgumentException(
                        "duplicate plugin id in profile package selection: "
                            + pluginId.value());
                }
            }
            var selections = prepared.values().stream().map(transaction -> {
                var published = transaction.save();
                return new PluginSelection(published.pluginId(),
                    published.packageRevision(), true);
            }).toList();
            result = registry.deploy(new PluginDeploymentRequest(selections,
                graph, paths.configContext())).block();
        } catch (RuntimeException | Error failure) {
            closeTransactions(transactions, failure);
            throw failure;
        }
        closeTransactions(transactions, null);
        return result;
    }

    private static void closeTransactions(
        List<PluginPackageInstallTransaction> transactions, Throwable primary) {
        Throwable failure = primary;
        for (var transaction : transactions.reversed()) {
            try { transaction.close(); }
            catch (Throwable cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        }
        if (primary == null && failure != null) throwUnchecked(failure);
    }

    @Override public synchronized void close() {
        if (closed) {
            if (closeFailure != null) throwUnchecked(closeFailure);
            return;
        }
        closed = true;
        Throwable failure = null;
        try { engine.close(); } catch (Throwable error) { failure = error; }
        try { audit.close(); } catch (Throwable error) { failure = append(failure, error); }
        terminationExecutor.shutdownNow();
        if (failure != null) { closeFailure = failure; throwUnchecked(failure); }
    }

    private static void close(Throwable failure, AutoCloseable resource) {
        if (resource == null) return;
        try { resource.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
    }
    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }
    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException("CLI host operation failed", failure);
    }
}
