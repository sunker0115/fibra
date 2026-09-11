package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.event.EventMode;

import java.util.List;

/** 一个 RuntimeDomain 在 lifecycle lane 上取得的只读诊断快照。 */
public record RuntimeDomainSnapshot(String name,
                                    List<Plugin> plugins,
                                    List<Service> services,
                                    List<Event> events,
                                    List<CleanupFailure> cleanupFailures) {
    public RuntimeDomainSnapshot {
        plugins = List.copyOf(plugins);
        services = List.copyOf(services);
        events = List.copyOf(events);
        cleanupFailures = List.copyOf(cleanupFailures);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String name;
        private List<Plugin> plugins = List.of();
        private List<Service> services = List.of();
        private List<Event> events = List.of();
        private List<CleanupFailure> cleanupFailures = List.of();
        public Builder name(String value) { name = value; return this; }
        public Builder plugins(List<Plugin> value) { plugins = value; return this; }
        public Builder services(List<Service> value) { services = value; return this; }
        public Builder events(List<Event> value) { events = value; return this; }
        public Builder cleanupFailures(List<CleanupFailure> value) { cleanupFailures = value; return this; }
        public RuntimeDomainSnapshot build() {
            return new RuntimeDomainSnapshot(name, plugins, services, events, cleanupFailures);
        }
    }

    /** 失败事实只包含稳定所有者身份和文字描述，不泄露资源或 Throwable。 */
    public record CleanupFailure(long ownerIdentity, String resource, String failure) { }

    public record Plugin(long identity, Long parentIdentity, String instanceId, String pluginId,
                         PluginInstanceState state,
                         List<Dependency> dependencies,
                         String failure) {
        public Plugin {
            dependencies = List.copyOf(dependencies);
        }

        public List<ServiceIdentity> waitingFor() {
            return dependencies.stream().filter(value -> value.provider() == null)
                .map(Dependency::service).toList();
        }
    }

    public record Dependency(ServiceIdentity service, OwnerIdentity provider) {
    }

    public record ServiceIdentity(String name, String type, String realm) {
    }

    public record OwnerIdentity(String kind, String id) {
    }

    public record Service(ServiceIdentity service,
                          OwnerIdentity effectiveProvider,
                          List<OwnerIdentity> shadowedProviders) {
        public Service {
            shadowedProviders = List.copyOf(shadowedProviders);
        }
    }

    public record Event(String name, EventMode mode, String listenerType,
                        List<Listener> listeners) {
        public Event {
            listeners = List.copyOf(listeners);
        }
    }

    public record Listener(OwnerIdentity owner, int order, boolean once,
                           boolean global) {
    }
}
