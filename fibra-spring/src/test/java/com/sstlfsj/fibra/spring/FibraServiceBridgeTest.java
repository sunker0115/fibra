package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.engine.DeploymentTargetStore;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.HostServiceRegistry;
import com.sstlfsj.fibra.runtime.java.JavaRuntimeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FibraServiceBridgeTest {
    @TempDir Path work;

    @Test
    void freezesExplicitSpringServicesWhenTheNewEngineBootstraps() {
        var services = new HostServiceRegistry();
        var bridge = new FibraServiceBridge(services);
        var registration = bridge.register(ServiceKey.of("greeting", Greeting.class),
            name -> "hello " + name);
        try (var packages = new PluginPackageStore(work.resolve("packages"));
             var targets = DeploymentTargetStore.inMemory();
             var engine = FibraEngine.builder(packages, targets).hostServices(services)
                 .hostTerminationPort(ignored -> { })
                 .runtimeProvider(new JavaRuntimeProvider(List.of())).build()) {
            engine.startAsync().block();
            assertEquals("hello fibra", registration.value().greet("fibra"));
            assertThrows(IllegalStateException.class,
                () -> bridge.register(ServiceKey.of("late", String.class), "late"));
        }
    }

    private interface Greeting { String greet(String name); }
}
