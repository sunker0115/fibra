package com.sstlfsj.fibra.example;

import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.PluginPackage;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.engine.PublishedRuntime;
import com.sstlfsj.fibra.engine.PublishedView;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.example.sanitizer.ContentSanitizerContribution;
import com.sstlfsj.fibra.example.sanitizer.SanitizeRequest;
import com.sstlfsj.fibra.example.sanitizer.SanitizeResult;
import com.sstlfsj.fibra.registry.PluginInstallRequest;
import com.sstlfsj.fibra.registry.PluginRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
final class SanitizerPluginManager implements ApplicationRunner {
    static final String INSTANCE_ID = "default-sanitizer";
    private static final PluginId PLUGIN_ID = new PluginId("content-sanitizer");
    private static final String FACET_ID = "main";
    private static final String DEFINITION_ID = "content-sanitizer";
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(10);

    private final SanitizerExampleProperties properties;
    private final PluginRegistry registry;
    private final PublishedRuntime published;

    SanitizerPluginManager(SanitizerExampleProperties properties,
                           PluginRegistry registry,
                           PublishedRuntime published) {
        this.properties = properties;
        this.registry = registry;
        this.published = published;
    }

    @Override
    public void run(ApplicationArguments args) {
        var source = properties.pluginDirectory().toAbsolutePath().normalize();
        var sourcePackage = PluginPackage.read(source);
        var selected = registry.snapshot().selections().get(PLUGIN_ID);
        if (selected == null) {
            registry.install(new PluginInstallRequest(source, true))
                .block(OPERATION_TIMEOUT);
        } else if (!selected.packageRevision().equals(sourcePackage.packageDigest())) {
            registry.upgrade(source).block(OPERATION_TIMEOUT);
        } else if (!selected.enabled()) {
            registry.enablePackage(PLUGIN_ID).block(OPERATION_TIMEOUT);
        }
        var desired = DesiredInputEntry.builder(INSTANCE_ID,
                new PluginDefinitionRef(PLUGIN_ID.value(), FACET_ID, DEFINITION_ID))
            .config(com.sstlfsj.fibra.value.LiteralValue.of(Map.of(
                "replacement", "[REDACTED]",
                "rules", List.of("email", "bearer-token", "api-key"))))
            .build();
        registry.upsert(null, desired).block(OPERATION_TIMEOUT);
    }

    SanitizeResult sanitize(SanitizeRequest request) {
        var view = published.current();
        return published.invoke(view.viewRevision(), identity(view, ContentSanitizerContribution.KIND,
            ContentSanitizerContribution.id(INSTANCE_ID)), ContentSanitizerContribution.KIND,
            ContentSanitizerContribution.id(INSTANCE_ID), request)
            .block(OPERATION_TIMEOUT);
    }

    private static long identity(PublishedView view, ContributionKind<?, ?, ?> kind, ContributionId id) {
        return view.contributions().entries().stream()
            .filter(entry -> entry.kind().equals(kind.name()) && entry.id().equals(id))
            .map(entry -> entry.registrationIdentity()).findFirst().orElseThrow();
    }

    PluginRegistry registry() {
        return registry;
    }
}
