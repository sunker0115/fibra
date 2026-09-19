package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.runtime.RuntimeDomainSnapshot;
import java.util.List;

public record RuntimeDiagnostics(String domainName,
    List<RuntimeDomainSnapshot.Plugin> plugins,
    List<RuntimeDomainSnapshot.Service> services,
    List<RuntimeDomainSnapshot.Event> events,
    List<RuntimeDomainSnapshot.CleanupFailure> cleanupFailures) {
    public RuntimeDiagnostics {
        plugins = List.copyOf(plugins);
        services = List.copyOf(services);
        events = List.copyOf(events);
        cleanupFailures = List.copyOf(cleanupFailures);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String domainName;
        private List<RuntimeDomainSnapshot.Plugin> plugins;
        private List<RuntimeDomainSnapshot.Service> services;
        private List<RuntimeDomainSnapshot.Event> events;
        private List<RuntimeDomainSnapshot.CleanupFailure> cleanupFailures = List.of();
        public Builder domainName(String value) { domainName = value; return this; }
        public Builder plugins(List<RuntimeDomainSnapshot.Plugin> value) { plugins = value; return this; }
        public Builder services(List<RuntimeDomainSnapshot.Service> value) { services = value; return this; }
        public Builder events(List<RuntimeDomainSnapshot.Event> value) { events = value; return this; }
        public Builder cleanupFailures(List<RuntimeDomainSnapshot.CleanupFailure> value) { cleanupFailures = value; return this; }
        public RuntimeDiagnostics build() { return new RuntimeDiagnostics(domainName, plugins, services, events, cleanupFailures); }
    }
}
