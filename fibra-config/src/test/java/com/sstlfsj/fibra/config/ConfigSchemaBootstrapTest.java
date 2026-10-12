package com.sstlfsj.fibra.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ConfigSchemaBootstrapTest {
    @Test
    void exposesTheApprovedPureConfigurationContract() {
        for (var name : new String[]{"ConfigSchemaSource", "ConfigSchema", "ConfigViolation",
            "ConfigValidationInput", "ConfigBatchFailure", "DefinitionConfigDocument",
            "ConfigSchemaException"}) {
            assertDoesNotThrow(() -> Class.forName("com.sstlfsj.fibra.config." + name));
        }
    }
}
