package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.HostServiceRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FibraServiceBridgeTest {
    @Test
    void collectsAnExplicitHostServiceAndFreezesItAtEngineStart() {
        var key = ServiceKey.of("greeting", Greeting.class);
        var hostServices = new HostServiceRegistry();
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .hostServices(hostServices).build()) {
            var bridge = new FibraServiceBridge(hostServices);
            var registration = bridge.register(key, name -> "hello " + name);

            assertEquals("hello fibra",
                registration.value().greet("fibra"));
            var started = engine.start().block();
            assertTrue(started.diagnostics().services().stream()
                .anyMatch(service -> service.service().name().equals(key.name())));
            assertThrows(IllegalStateException.class, () ->
                bridge.register(ServiceKey.of("late", String.class), "late"));

            registration.dispose().block();
            assertTrue(engine.published().current().diagnostics().services().stream()
                .anyMatch(service -> service.service().name().equals(key.name())));
        }
    }

    private interface Greeting {
        String greet(String name);
    }
}
