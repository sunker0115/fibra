package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.config.PublicationRequirement;
import com.sstlfsj.fibra.runtime.RuntimeDomainSnapshot;

import java.util.List;

/** 当前已发布运行代内部的不可变事实投影。 */
public record RuntimeDiagnostics(String generationRevision,
                                 String domainName,
                                 List<Plugin> plugins,
                                 List<RuntimeDomainSnapshot.Service> services,
                                 List<RuntimeDomainSnapshot.Event> events,
                                 String failure) {
    public RuntimeDiagnostics {
        plugins = List.copyOf(plugins);
        services = List.copyOf(services);
        events = List.copyOf(events);
    }

    public record Plugin(String instanceId, String pluginId,
                         PluginInstanceState state,
                         PublicationRequirement publicationRequirement,
                         PublicationImpact publicationImpact,
                         List<RuntimeDomainSnapshot.Dependency> dependencies,
                         String failure) {
        public Plugin {
            dependencies = List.copyOf(dependencies);
        }

        public List<RuntimeDomainSnapshot.ServiceIdentity> waitingFor() {
            return dependencies.stream().filter(value -> value.provider() == null)
                .map(RuntimeDomainSnapshot.Dependency::service).toList();
        }
    }

    public enum PublicationImpact {
        NONE,
        BLOCKING
    }
}
