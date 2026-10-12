package com.sstlfsj.fibra.artifact;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginConfigurationResourceBootstrapTest {
    @Test
    void exposesOnlyTheFixedLazyResourceContract() throws Exception {
        var method = PluginPackageRecord.class.getMethod("configurationResource");
        var resource = method.getReturnType();
        assertEquals("com.sstlfsj.fibra.artifact.PluginConfigurationResource", resource.getName());
        assertTrue(resource.isInterface());
        assertTrue(AutoCloseable.class.isAssignableFrom(resource));
        assertEquals(byte[].class, resource.getMethod("read").getReturnType());
        assertEquals(void.class, resource.getMethod("close").getReturnType());
        assertEquals(2, resource.getDeclaredMethods().length);
    }
}
