package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FibraServiceBridgeTest {
    @Test
    void registersAndRevokesAnExplicitHostService() {
        var key = ServiceKey.of("greeting", Greeting.class);
        try (var root = FibraRuntime.create()) {
            var bridge = new FibraServiceBridge(root);
            var registration = bridge.register(key, name -> "hello " + name);

            assertEquals("hello fibra",
                root.service(key).invoke((invocation, service) -> service.greet("fibra")));
            registration.dispose().block();
            assertNull(root.get(key, true));
        }
    }

    private interface Greeting {
        String greet(String name);
    }
}
