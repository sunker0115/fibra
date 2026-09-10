package com.sstlfsj.fibra.example.dependency;

import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.config.DesiredEntry;
import com.sstlfsj.fibra.config.DesiredGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.engine.FileTransactionJournal;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.PluginCatalog;
import com.sstlfsj.fibra.engine.PluginCatalogEntry;
import com.sstlfsj.fibra.registry.InMemoryPluginAuditRepository;
import com.sstlfsj.fibra.registry.PluginDeploymentRequest;
import com.sstlfsj.fibra.registry.PluginEnableRequest;
import com.sstlfsj.fibra.registry.PluginInstallRequest;
import com.sstlfsj.fibra.registry.PluginRegistry;
import com.sstlfsj.fibra.runtime.java.JavaPluginRuntimeAdapter;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class PluginDependencyScenario implements AutoCloseable {
    public static final ArtifactId CONTRACT_ARTIFACT =
        new ArtifactId("shipping-rate-contract");
    public static final ArtifactId PROVIDER_ARTIFACT =
        new ArtifactId("shipping-rate-provider");
    public static final ArtifactId CONSUMER_ARTIFACT =
        new ArtifactId("checkout-quote-consumer");
    public static final String PROVIDER_INSTANCE = "default-shipping-rate";
    public static final String CONSUMER_INSTANCE = "default-checkout-quote";
    private static final String PROVIDER_DEFINITION = "shipping-rate-provider";
    private static final String CONSUMER_DEFINITION = "checkout-quote-consumer";
    private static final String PROJECTION_DEFINITION = "checkout-quote-projection";
    private static final String PROJECTION_INSTANCE = "application-checkout-quote";
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(10);

    private final FibraEngine engine;
    private final PluginRegistry registry;
    private final AtomicReference<CheckoutQuote> projectedQuote;
    private boolean closed;

    private PluginDependencyScenario(FibraEngine engine, PluginRegistry registry,
                                     AtomicReference<CheckoutQuote> projectedQuote) {
        this.engine = engine;
        this.registry = registry;
        this.projectedQuote = projectedQuote;
    }

    public static PluginDependencyScenario open(Path contract,
                                                Path provider,
                                                Path consumer,
                                                Path storageRoot) {
        var projectedQuote = new AtomicReference<CheckoutQuote>();
        var projection = projection(projectedQuote);
        var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(projection,
                value -> value == null ? null : (Integer) value)))
            .artifactStore(new ArtifactStore(storageRoot.resolve("artifacts")))
            .journal(new FileTransactionJournal(storageRoot.resolve("transactions")))
            .runtimeAdapter(new JavaPluginRuntimeAdapter())
            .build();
        var registry = new PluginRegistry(engine,
            new InMemoryPluginAuditRepository());
        try {
            engine.start().block(OPERATION_TIMEOUT);
            registry.deploy(new PluginDeploymentRequest(
                List.of(
                    install(CONTRACT_ARTIFACT, "1.0.0", contract),
                    install(PROVIDER_ARTIFACT, "1.0.0", provider),
                    install(CONSUMER_ARTIFACT, "1.0.0", consumer)),
                new DesiredGraph(List.of(
                    desired(PROVIDER_INSTANCE, PROVIDER_DEFINITION),
                    desired(CONSUMER_INSTANCE, CONSUMER_DEFINITION),
                    DesiredEntry.builder(PROJECTION_INSTANCE, PROJECTION_DEFINITION)
                        .config(6_000).build()))))
                .block(OPERATION_TIMEOUT);
            return new PluginDependencyScenario(engine, registry, projectedQuote);
        } catch (RuntimeException | Error failure) {
            engine.close();
            throw failure;
        }
    }

    public CheckoutQuote projectedQuote() {
        var quote = projectedQuote.get();
        if (quote == null) {
            throw new IllegalStateException("checkout quote has not been projected");
        }
        return quote;
    }

    public void disableProvider() {
        registry.disable(PROVIDER_INSTANCE).block(OPERATION_TIMEOUT);
    }

    public void upgradeProvider(Path artifact, String version) {
        registry.upgrade(install(PROVIDER_ARTIFACT, version, artifact))
            .block(OPERATION_TIMEOUT);
    }

    public void upgradeContract(Path artifact, String version) {
        registry.upgrade(install(CONTRACT_ARTIFACT, version, artifact))
            .block(OPERATION_TIMEOUT);
    }

    public void stopInDependencyOrder() {
        registry.disable(PROJECTION_INSTANCE).block(OPERATION_TIMEOUT);
        registry.disable(CONSUMER_INSTANCE).block(OPERATION_TIMEOUT);
        registry.disable(PROVIDER_INSTANCE).block(OPERATION_TIMEOUT);
    }

    public void startInDependencyOrder() {
        registry.enable(PluginEnableRequest.of(PROVIDER_INSTANCE,
            PROVIDER_DEFINITION, null)).block(OPERATION_TIMEOUT);
        registry.enable(PluginEnableRequest.of(CONSUMER_INSTANCE,
            CONSUMER_DEFINITION, null)).block(OPERATION_TIMEOUT);
        registry.enable(PluginEnableRequest.of(PROJECTION_INSTANCE,
            PROJECTION_DEFINITION, 6_000)).block(OPERATION_TIMEOUT);
    }

    public PluginInstanceState state(String instanceId) {
        return registry.get(instanceId)
            .flatMap(value -> java.util.Optional.ofNullable(value.observed()))
            .map(value -> value.state())
            .orElse(PluginInstanceState.DISPOSED);
    }

    public String generationRevision() {
        return engine.published().current().generationRevision();
    }

    public PluginRegistry registry() {
        return registry;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        engine.close();
    }

    private static PluginInstallRequest install(ArtifactId artifactId,
                                                String version,
                                                Path source) {
        return PluginInstallRequest.builder().artifactId(artifactId)
            .runtimeId(JavaPluginRuntimeAdapter.RUNTIME_ID)
            .version(version).source(source).build();
    }

    private static DesiredEntry desired(String instanceId,
                                        String definitionName) {
        return DesiredEntry.builder(instanceId, definitionName).build();
    }

    private static PluginDefinition<Integer> projection(
        AtomicReference<CheckoutQuote> projectedQuote) {
        return PluginDefinition.builder(PROJECTION_DEFINITION, Integer.class,
                () -> (context, subtotalCents) -> {
                    var quotes = context.services().reference(
                        CheckoutQuoteServices.CHECKOUT_QUOTE);
                    projectedQuote.set(quotes.invoke((invocation, service) ->
                        service.quote(subtotalCents)));
                    return reactor.core.publisher.Mono.empty();
                })
            .require(CheckoutQuoteServices.CHECKOUT_QUOTE)
            .validator(value -> value == null ? 6_000 : value)
            .build();
    }
}
