package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.PublicationRequirement;
import com.sstlfsj.fibra.engine.PluginInstanceSnapshot;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolDescriptor;
import com.sstlfsj.fibra.plugins.tool.ToolException;
import com.sstlfsj.fibra.plugins.tool.ToolFailureCode;
import com.sstlfsj.fibra.registry.PluginAuditDeliveryFailure;
import com.sstlfsj.fibra.registry.TargetSaveState;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraCliTest {
    @Test
    void helpReturnsSuccessWithoutStartingAHost() {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {"--help"}, new ByteArrayInputStream(new byte[0]),
            writer(output), writer(error), paths -> failIfHostStarts());

        assertEquals(0, exitCode);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Usage: fibra"));
        assertEquals("", error.toString(StandardCharsets.UTF_8));
    }

    @Test
    void versionReturnsSuccessWithoutStartingAHost() {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {"--version"}, new ByteArrayInputStream(new byte[0]),
            writer(output), writer(error), paths -> failIfHostStarts());

        assertEquals(0, exitCode);
        assertTrue(output.toString(StandardCharsets.UTF_8).startsWith("fibra "));
        assertEquals("", error.toString(StandardCharsets.UTF_8));
    }

    @Test
    void helpSubcommandUsesTheSameCommandTreeWithoutStartingAHost() {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {"help", "tools"},
            new ByteArrayInputStream(new byte[0]), writer(output), writer(error),
            paths -> failIfHostStarts());

        assertEquals(0, exitCode);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Usage: fibra tools"));
        assertEquals("", error.toString(StandardCharsets.UTF_8));
    }

    @Test
    void unknownCommandReturnsUsageExitCode() {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {"unknown"}, new ByteArrayInputStream(new byte[0]),
            writer(output), writer(error), paths -> failIfHostStarts());

        assertEquals(2, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("unknown"));
    }

    @Test
    void noArgumentsPrintUsageWithoutStartingAHost() {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[0], new ByteArrayInputStream(new byte[0]),
            writer(output), writer(error), paths -> failIfHostStarts());

        assertEquals(2, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("Usage: fibra"));
    }

    @Test
    void invalidToolInputIsRejectedBeforeStartingAHost() {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {
            "tools", "invoke", "example", "read", "--input", "[]"
        }, new ByteArrayInputStream(new byte[0]), writer(output), writer(error),
            paths -> failIfHostStarts());

        assertEquals(2, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("JSON object"));
    }

    @Test
    void trailingOrDuplicateToolInputIsRejectedBeforeStartingAHost() {
        for (var input : new String[] {"{} {}", "{\"key\":1,\"key\":2}"}) {
            var output = new ByteArrayOutputStream();
            var error = new ByteArrayOutputStream();

            var exitCode = FibraCli.run(new String[] {
                "tools", "invoke", "example", "read", "--input", input
            }, new ByteArrayInputStream(new byte[0]), writer(output), writer(error),
                paths -> failIfHostStarts());

            assertEquals(2, exitCode);
            assertTrue(error.toString(StandardCharsets.UTF_8).contains("JSON object"));
        }
    }

    @Test
    void invalidProfileNameIsAUsageFailureBeforeStartingAHost() {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {
            "--profile", "../other", "plugins", "list"
        }, new ByteArrayInputStream(new byte[0]), writer(output), writer(error),
            paths -> failIfHostStarts());

        assertEquals(2, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("profile must be a safe name"));
    }

    @Test
    void globalPathOptionsAreParsedBeforeOpeningAHost() {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {
            "--home", "build/fibra-home", "--profile", "team-a", "--config-root", "build/config",
            "--plugins-root", "build/plugins", "--data-root", "build/data", "--node", "custom-node",
            "--help"
        }, new ByteArrayInputStream(new byte[0]), writer(output), writer(error),
            paths -> failIfHostStarts());

        assertEquals(0, exitCode);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Usage: fibra"));
    }

    @Test
    void pluginsListPrintsEmptyHostAsSingleJsonLine(@TempDir Path home) throws Exception {
        var profiles = Files.createDirectories(home.resolve("config/profiles"));
        Files.writeString(profiles.resolve("default.yaml"), "[]\n");
        Files.writeString(profiles.resolve("default.artifacts.yaml"), "[]\n");
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {"--home", home.toString(), "plugins", "list"},
            new ByteArrayInputStream(new byte[0]), writer(output), writer(error));

        assertEquals(0, exitCode);
        assertTrue(output.toString(StandardCharsets.UTF_8)
            .matches("\\{\\\"artifacts\\\":\\[\\],\\\"auditFailures\\\":\\[\\],"
                + "\\\"instances\\\":\\[\\],\\\"viewRevision\\\":\\\".+\\\"}\\R"));
        assertEquals("", error.toString(StandardCharsets.UTF_8));
    }

    @Test
    void globalPathOptionsSelectTheConfiguredProfile(@TempDir Path work) throws Exception {
        var config = Files.createDirectories(work.resolve("custom-config/profiles"));
        Files.writeString(config.resolve("team-a.yaml"), "[]\n");
        Files.writeString(config.resolve("team-a.artifacts.yaml"), "[]\n");
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {
            "--home", work.resolve("home").toString(), "--profile", "team-a",
            "--config-root", work.resolve("custom-config").toString(),
            "--plugins-root", work.resolve("custom-plugins").toString(),
            "--data-root", work.resolve("custom-data").toString(), "--node", "custom-node",
            "plugins", "list"
        }, new ByteArrayInputStream(new byte[0]), writer(output), writer(error));

        assertEquals(0, exitCode);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"artifacts\":[]"));
        assertEquals("", error.toString(StandardCharsets.UTF_8));
        assertTrue(Files.isDirectory(work.resolve("custom-data/profiles/team-a")));
    }

    @Test
    void mainExecutionPathRemovesShutdownHookAfterClosingHost(@TempDir Path home) throws Exception {
        var profiles = Files.createDirectories(home.resolve("config/profiles"));
        Files.writeString(profiles.resolve("default.yaml"), "[]\n");
        Files.writeString(profiles.resolve("default.artifacts.yaml"), "[]\n");

        assertEquals(0, executeListWithShutdownHook(home));
        assertEquals(0, executeListWithShutdownHook(home));
    }

    @Test
    void installAcceptsAnExplicitPackageOutsideTheCandidateDirectory(@TempDir Path work)
        throws Exception {
        var profiles = Files.createDirectories(work.resolve("home/config/profiles"));
        Files.writeString(profiles.resolve("default.yaml"), "[]\n");
        Files.writeString(profiles.resolve("default.artifacts.yaml"), "[]\n");
        var external = Files.createDirectories(work.resolve("external-package"));
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {
            "--home", work.resolve("home").toString(), "plugins", "install", external.toString()
        }, new ByteArrayInputStream(new byte[0]), writer(output), writer(error));

        assertEquals(4, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("插件探测失败"));
        assertFalse(error.toString(StandardCharsets.UTF_8).contains("候选插件目录"));
    }

    @Test
    void applyValidationFailureIsABusinessFailureAfterRestoringTheHost(@TempDir Path home)
        throws Exception {
        var profiles = Files.createDirectories(home.resolve("config/profiles"));
        Files.writeString(profiles.resolve("default.yaml"), "[]\n");
        Files.writeString(profiles.resolve("default.artifacts.yaml"), "[]\n");
        assertEquals(0, FibraCli.run(new String[] {"--home", home.toString(), "plugins", "list"},
            new ByteArrayInputStream(new byte[0]), writer(new ByteArrayOutputStream()),
            writer(new ByteArrayOutputStream())));
        Files.writeString(profiles.resolve("default.artifacts.yaml"), "not-a-list\n");
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {"--home", home.toString(), "apply"},
            new ByteArrayInputStream(new byte[0]), writer(output), writer(error));

        assertEquals(4, exitCode);
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("应用配置失败"));
    }

    @Test
    void toolFailureIsPrintedAsStableJsonCode(@TempDir Path home) throws Exception {
        failingToolProfile(home);
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {
            "--home", home.toString(), "tools", "invoke", "failer", "fail", "--input", "{}"
        }, new ByteArrayInputStream(new byte[0]), writer(output), writer(error));

        assertEquals(4, exitCode);
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        var failure = error.toString(StandardCharsets.UTF_8);
        assertTrue(failure.matches("\\{\\\"content\\\":\\[\\{\\\"text\\\":\\\"Error: simulated timeout\\\","
            + "\\\"type\\\":\\\"text\\\"}\\],\\\"error\\\":\\{\\\"code\\\":\\\"TIMEOUT\\\","
            + "\\\"message\\\":\\\"simulated timeout\\\"},\\\"isError\\\":true,"
            + "\\\"viewRevision\\\":\\\".+\\\"}\\R"));
    }

    @Test
    void instanceJsonDistinguishesDisabledAndFailedRuntimeState() {
        var disabled = FibraCli.instance("disabled",
            DesiredInputEntry.builder("disabled", "sample").enabled(false).build(), null);
        var failedSnapshot = PluginInstanceSnapshot.builder().identity(42).instanceId("failed")
            .definitionName("sample").config(LiteralValue.of(null))
            .state(PluginInstanceState.FAILED)
            .publicationRequirement(PublicationRequirement.ACTIVE_REQUIRED)
            .failure("start failed").build();
        var failed = FibraCli.instance("failed",
            DesiredInputEntry.builder("failed", "sample").build(), failedSnapshot);

        assertEquals(false, disabled.get("enabled"));
        assertEquals(false, disabled.get("observed"));
        assertEquals(null, disabled.get("state"));
        assertEquals(true, disabled.get("requirementSatisfied"));
        assertEquals(null, disabled.get("failure"));
        assertEquals(true, failed.get("enabled"));
        assertEquals(true, failed.get("observed"));
        assertEquals("FAILED", failed.get("state"));
        assertEquals("ACTIVE_REQUIRED", failed.get("publicationRequirement"));
        assertEquals(false, failed.get("requirementSatisfied"));
        assertEquals("start failed", failed.get("failure"));
        assertFalse(failed.containsKey("config"));
    }

    @Test
    void auditDeliveryFailureJsonPreservesSuccessfulMutationDiagnostics() {
        var failure = PluginAuditDeliveryFailure.builder().timestamp(Instant.parse("2026-09-12T00:00:00Z"))
            .operation("install").target("sample").succeeded(true)
            .targetSaveState(TargetSaveState.SAVED).viewRevision("view-2")
            .detail("audit unavailable").build();

        assertEquals(Map.of("timestamp", "2026-09-12T00:00:00Z", "operation", "install",
            "target", "sample", "succeeded", true, "targetSaveState", "SAVED",
            "viewRevision", "view-2", "detail", "audit unavailable"),
            FibraCli.auditFailure(failure));
    }

    private static int executeListWithShutdownHook(Path home) {
        return FibraCli.execute(new String[] {"--home", home.toString(), "plugins", "list"},
            new ByteArrayInputStream(new byte[0]), new PrintWriter(new ByteArrayOutputStream(), true),
            new PrintWriter(new ByteArrayOutputStream(), true), CliHost::open, true);
    }

    private static PrintWriter writer(ByteArrayOutputStream output) {
        return new PrintWriter(output, true, StandardCharsets.UTF_8);
    }

    private static void failingToolProfile(Path home) throws Exception {
        var profiles = Files.createDirectories(home.resolve("config/profiles"));
        Files.writeString(profiles.resolve("default.yaml"), "- {id: failer, plugin: failer}\n");
        Files.writeString(profiles.resolve("default.artifacts.yaml"), "- failer\n");
        var root = Files.createDirectories(home.resolve("plugins/failer"));
        var lib = Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("plugin.properties"), """
            formatVersion=1
            runtime=java
            payload=lib/main.jar
            """);
        try (var jar = new JarOutputStream(Files.newOutputStream(lib.resolve("main.jar")))) {
            jar.putNextEntry(new JarEntry("META-INF/fibra/plugin.yaml"));
            jar.write(("""
                id: failer
                version: 1.0.0
                entrypoint: %s
                requires: []
                """).formatted(FailingToolEntrypoint.class.getName()).getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            var className = FailingToolEntrypoint.class.getName().replace('.', '/') + ".class";
            jar.putNextEntry(new JarEntry(className));
            try (var input = FibraCliTest.class.getResourceAsStream('/' + className)) {
                if (input == null) throw new IllegalStateException("missing test entrypoint class");
                jar.write(input.readAllBytes());
            }
            jar.closeEntry();
        }
    }

    public static final class FailingToolEntrypoint implements PluginEntrypoint<Void> {
        @Override public PluginDefinition<Void> definition() {
            return PluginDefinition.builder("failer", Void.class, FailingTool::new)
                .require(ContributionServices.REGISTRAR).build();
        }
    }

    private static final class FailingTool implements Plugin<Void> {
        @Override public Mono<Void> start(com.sstlfsj.fibra.Context context, Void config) {
            var descriptor = new ToolDescriptor("Fail", "Always fails.",
                (LiteralValue.ObjectValue) LiteralValue.of(Map.of("type", "object")),
                (LiteralValue.ObjectValue) LiteralValue.of(Map.of("type", "object")));
            return context.services().require(ContributionServices.REGISTRAR).register(context,
                ToolContributions.KIND, "failer", "fail", descriptor,
                (invocation, request) -> Mono.error(new ToolException(
                    ToolFailureCode.TIMEOUT, "simulated timeout"))).then();
        }
    }

    private static CliHost failIfHostStarts() {
        throw new AssertionError("不应启动宿主");
    }
}
