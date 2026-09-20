package com.sstlfsj.fibra.plugins.storage;

import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StorageContractTest {
    @Test
    void documentIsAnImmutableRevisionedLiteralSnapshot() {
        var nested = new LinkedHashMap<String, Object>();
        nested.put("enabled", true);
        var source = new LinkedHashMap<String, Object>();
        source.put("feature", nested);

        var document = ConfigDocument.of(3, source);
        nested.put("enabled", false);
        source.put("late", "ignored");

        assertEquals(3, document.revision());
        assertEquals(Map.of("enabled", true), document.find("feature")
            .orElseThrow().toJava());
        assertFalse(document.find("late").isPresent());
        assertThrows(UnsupportedOperationException.class,
            () -> document.values().put("x", new LiteralValue.StringValue("y")));
        assertThrows(IllegalArgumentException.class,
            () -> ConfigDocument.of(-1, Map.of()));
        assertThrows(IllegalArgumentException.class,
            () -> ConfigDocument.of(0, Map.of(" ", true)));
    }

    @Test
    void changesHaveClosedPutAndRemoveShapes() {
        var value = new LiteralValue.StringValue("on");
        var put = new ConfigChange(4, "feature", ConfigChangeOperation.PUT, value);
        var removed = new ConfigChange(5, "feature", ConfigChangeOperation.REMOVED, null);

        assertEquals(value, put.value());
        assertEquals(ConfigChangeOperation.REMOVED, removed.operation());
        assertThrows(IllegalArgumentException.class,
            () -> new ConfigChange(1, "feature", ConfigChangeOperation.PUT, null));
        assertThrows(IllegalArgumentException.class,
            () -> new ConfigChange(1, "feature", ConfigChangeOperation.REMOVED, value));
    }

    @Test
    void serviceAndErrorsExposeStableMachineContracts() {
        assertEquals("fibra.storage", StorageServices.CONFIG_STORE.name());
        assertEquals(ConfigStore.class, StorageServices.CONFIG_STORE.type());

        var cause = new IllegalStateException("disk");
        var failure = new StorageException(StorageErrorCode.PERSISTENCE_FAILED,
            "cannot persist", cause);
        assertEquals(StorageErrorCode.PERSISTENCE_FAILED, failure.code());
        assertEquals(cause, failure.getCause());
    }

    @Test
    void runtimeDescriptorDoesNotDuplicateLogicalPackageMetadata() throws Exception {
        try (var input = getClass().getResourceAsStream("/META-INF/fibra/plugin.yaml")) {
            var manifest = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("{}\n", manifest);
        }
    }
}
