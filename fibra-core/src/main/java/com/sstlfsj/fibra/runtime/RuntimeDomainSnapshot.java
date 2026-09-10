package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.event.EventMode;

import java.util.List;

/** 一个 RuntimeDomain 在 lifecycle lane 上取得的只读诊断快照。 */
public record RuntimeDomainSnapshot(String name,
                                    List<Plugin> plugins,
                                    List<Service> services,
                                    List<Event> events) {
    public RuntimeDomainSnapshot {
        plugins = List.copyOf(plugins);
        services = List.copyOf(services);
        events = List.copyOf(events);
    }

    public record Plugin(String instanceId, String pluginId,
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
