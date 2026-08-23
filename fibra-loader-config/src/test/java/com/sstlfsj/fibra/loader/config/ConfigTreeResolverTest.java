package com.sstlfsj.fibra.loader.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTreeResolverTest {
    @Test
    void resolvesGroupsIncludesPatchesAndInheritedState(@TempDir Path work) throws Exception {
        var included = work.resolve("included.yaml");
        Files.writeString(included, """
            - id: provider
              name: provider-artifact
              config:
                value: original
            """);
        var root = work.resolve("root.yaml");
        Files.writeString(root, """
            - id: agents
              group: true
              disabled: true
              isolate:
                agent: true
                cache: shared
              intercept:
                logger:
                  level: INFO
              config:
                - id: main
                  name: agent-artifact
                  inject: [settings]
                  isolate:
                    cache: local
            - id: bundle
              include: ./included.yaml
              patches:
                - id: provider
                  name: provider-artifact
                  config:
                    value: patched
            """);
        var warnings = new ArrayList<FibraConfigWarning>();

        var snapshot = new ConfigTreeResolver(ConfigLimits.defaults(), warnings::add)
            .resolve(root, List.of());

        assertEquals(List.of("agents", "agents:main", "bundle", "bundle:provider"),
            snapshot.allEntries().stream().map(FibraConfigEntry::entryId).toList());
        var group = snapshot.resolve("agents").orElseThrow();
        var agent = snapshot.resolve("agents:main").orElseThrow();
        var provider = snapshot.resolve("bundle:provider").orElseThrow();
        assertEquals(FibraConfigEntry.Kind.GROUP, group.kind());
        assertFalse(group.disabled());
        assertTrue(group.declaredDisabled());
        assertTrue(agent.disabled());
        assertEquals(java.util.Collections.singletonMap("settings", null), agent.inject());
        assertEquals(Map.of("agent", true, "cache", "local"), agent.isolate());
        assertEquals(Map.of("logger", Map.of("level", "INFO")), agent.intercept());
        assertEquals(Map.of("value", "patched"), provider.config());
        assertEquals(included.toRealPath(), provider.source());
        assertEquals(List.of(), warnings);
    }

    @Test
    void rejectsUnknownFieldsDuplicateIdsAndInvalidNodeShapes(@TempDir Path work)
        throws Exception {
        var unknown = work.resolve("unknown.yaml");
        Files.writeString(unknown, """
            - id: first
              name: fixture
              typo: true
            """);
        var duplicate = work.resolve("duplicate.yaml");
        Files.writeString(duplicate, """
            - id: same
              name: fixture
            - id: same
              name: fixture
            """);
        var invalid = work.resolve("invalid.yaml");
        Files.writeString(invalid, """
            - id: broken
              group: true
              name: forbidden
              config: []
            """);
        var resolver = new ConfigTreeResolver(ConfigLimits.defaults(), ignored -> { });

        assertEquals(FibraConfigErrorStage.VALIDATE,
            assertThrows(FibraConfigException.class,
                () -> resolver.resolve(unknown, List.of())).stage());
        assertEquals(FibraConfigErrorStage.VALIDATE,
            assertThrows(FibraConfigException.class,
                () -> resolver.resolve(duplicate, List.of())).stage());
        assertEquals(FibraConfigErrorStage.VALIDATE,
            assertThrows(FibraConfigException.class,
                () -> resolver.resolve(invalid, List.of())).stage());
    }

    @Test
    void rejectsIncludeCyclesUsingRealPaths(@TempDir Path work) throws Exception {
        var first = work.resolve("first.yaml");
        var second = work.resolve("second.yaml");
        Files.writeString(first, """
            - id: second
              include: ./second.yaml
            """);
        Files.writeString(second, """
            - id: first
              include: ./first.yaml
            """);

        var error = assertThrows(FibraConfigException.class,
            () -> new ConfigTreeResolver(ConfigLimits.defaults(), ignored -> { })
                .resolve(first, List.of()));

        assertEquals(FibraConfigErrorStage.VALIDATE, error.stage());
        assertEquals("second:first", error.entryId());
    }

    @Test
    void rejectsColonInRawIdAndInvalidNameOnlyMaps(@TempDir Path work) throws Exception {
        var path = work.resolve("invalid.yaml");
        Files.writeString(path, """
            - id: bad:id
              name: fixture
              inject: [""]
              isolate:
                service: false
            """);

        var error = assertThrows(FibraConfigException.class,
            () -> new ConfigTreeResolver(ConfigLimits.defaults(), ignored -> { })
                .resolve(path, List.of()));

        assertEquals(FibraConfigErrorStage.VALIDATE, error.stage());
    }
}
