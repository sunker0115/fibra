package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.engine.FibraEngine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicApiBoundaryTest {
    @Test
    void implementationTypesDoNotBecomeExternalContracts() {
        var names = List.of(
            "com.sstlfsj.fibra.engine.DeploymentCandidate",
            "com.sstlfsj.fibra.engine.PluginCatalog",
            "com.sstlfsj.fibra.engine.PluginCatalogEntry",
            "com.sstlfsj.fibra.engine.DesiredBindingException",
            "com.sstlfsj.fibra.engine.UnknownRuntimeException",
            "com.sstlfsj.fibra.runtime.java.JavaFacetDescriptor",
            "com.sstlfsj.fibra.runtime.java.JavaRuntimeDriver",
            "com.sstlfsj.fibra.runtime.node.NodeFacetDescriptor",
            "com.sstlfsj.fibra.runtime.node.NodeEndpointManifest",
            "com.sstlfsj.fibra.runtime.node.NodeRuntimeDriver");
        assertAll(names.stream().map(name -> () ->
            assertFalse(Modifier.isPublic(type(name).getModifiers()), name)));
    }

    @Test
    void externalExtensionAndCompositionContractsRemainPublic() {
        var names = List.of(
            "com.sstlfsj.fibra.engine.runtime.RuntimeProvider",
            "com.sstlfsj.fibra.engine.runtime.RuntimeDriver",
            "com.sstlfsj.fibra.engine.runtime.RuntimeCandidate",
            "com.sstlfsj.fibra.engine.runtime.PreparedRuntimeGeneration",
            "com.sstlfsj.fibra.engine.runtime.RuntimeUnitGeneration",
            "com.sstlfsj.fibra.engine.runtime.RuntimeHostServices",
            "com.sstlfsj.fibra.runtime.java.JavaRuntimeProvider",
            "com.sstlfsj.fibra.runtime.java.JavaApiContract",
            "com.sstlfsj.fibra.runtime.node.NodeRuntimeProvider");
        assertAll(names.stream().map(name -> () ->
            assertTrue(Modifier.isPublic(type(name).getModifiers()), name)));
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, FibraEngine.class.getClassLoader());
    }
}
