package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraServiceBridgeTest {
    @Test
    void registersAndRevokesAnExplicitHostService() {
        var key = ServiceKey.of("greeting", Greeting.class);
        try (var runtime = FibraRuntime.create()) {
            var root = runtime.rootScope().context();
            var bridge = new FibraServiceBridge(root);
            var registration = bridge.register(key, name -> "hello " + name);

            assertEquals("hello fibra",
                root.services().require(key).greet("fibra"));
            registration.dispose().block();
            assertTrue(root.services().find(key).isEmpty());
        }
    }

    private interface Greeting {
        String greet(String name);
    }
}
