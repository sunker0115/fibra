package com.sstlfsj.fibra.engine.deployment;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BuiltInConfigurationBootstrapTest {
    @Test
    void exposesConfigurationMetadataAndImplementationIdentityWithoutLegacyBuilder() {
        assertDoesNotThrow(() -> BuiltInFacet.Builder.class.getMethod("configSchemas", Map.class));
        assertDoesNotThrow(() -> BuiltInFacet.class.getMethod("configSchemas"));
        assertDoesNotThrow(() -> BuiltInPluginPackage.Builder.class.getMethod("implementationDigest", String.class));
        assertThrows(NoSuchMethodException.class,
            () -> BuiltInPluginPackage.Builder.class.getMethod("packageDigest", String.class));
    }
}
