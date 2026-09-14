package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.cli.api.CliCommandContributions;
import com.sstlfsj.fibra.cli.api.CliCommandDescriptor;
import com.sstlfsj.fibra.cli.api.CliCommandOption;
import com.sstlfsj.fibra.cli.api.CliCommandResult;
import com.sstlfsj.fibra.cli.api.CliExitStatus;
import com.sstlfsj.fibra.cli.api.CliApplication;
import com.sstlfsj.fibra.cli.api.CliBootstrapCommand;
import com.sstlfsj.fibra.cli.api.CliTerminalRenderer;
import com.sstlfsj.fibra.cli.api.CliTerminalFrame;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.PublicationRequirement;
import com.sstlfsj.fibra.engine.PluginInstanceSnapshot;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolDescriptor;
import com.sstlfsj.fibra.plugins.tool.ToolException;
import com.sstlfsj.fibra.plugins.tool.ToolFailureCode;
import com.sstlfsj.fibra.registry.PluginAuditDeliveryFailure;
import com.sstlfsj.fibra.engine.TargetSaveState;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraCliTest {
    private static CliTerminalRenderer idleRenderer() {
        return size -> new CliTerminalFrame(List.of(""), java.util.Optional.empty());
    }

    @Test
    void referenceEntryPointIsNotAPublicPicocliCommandContract() {
        assertFalse(java.util.concurrent.Callable.class.isAssignableFrom(FibraCli.class));
        assertFalse(Runnable.class.isAssignableFrom(FibraCli.class));
    }

    @Test
    void cancellationOnlyOverridesSuccessfulHandlerResults() {
        assertEquals(130, FibraCli.projectExitStatus(CliCommandResult.success(), true));
        assertEquals(4, FibraCli.projectExitStatus(
            new CliCommandResult(CliExitStatus.INVOCATION_ERROR), true));
        assertEquals(7, FibraCli.projectExitStatus(
            new CliCommandResult(CliExitStatus.CLOSE_ERROR), true));
    }

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
    void unknownCommandReturnsUsageExitCodeAfterCheckingPublishedCommands(@TempDir Path home)
        throws Exception {
        var profiles = Files.createDirectories(home.resolve("config/profiles"));
        Files.writeString(profiles.resolve("default.yaml"), "[]\n");
        Files.writeString(profiles.resolve("default.artifacts.yaml"), "[]\n");
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {"--home", home.toString(), "unknown"},
            new ByteArrayInputStream(new byte[0]), writer(output), writer(error));

        assertEquals(2, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("unknown"));
    }

    @Test
    void dynamicCommandDiscoveryMapsHostStartupFailureToStableExitCode() {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {"dynamic"},
            new ByteArrayInputStream(new byte[0]), writer(output), writer(error),
            paths -> { throw new IllegalStateException("simulated startup failure"); });

        assertEquals(3, exitCode);
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertTrue(error.toString(StandardCharsets.UTF_8)
            .contains("无法启动宿主: simulated startup failure"));
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
    void publishedJavaCommandRunsAndRendersHelpThroughThePublicCli(@TempDir Path home)
        throws Exception {
        dynamicCommandProfile(home);
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {
            "--home", home.toString(), "echo", "--prefix", "hello-", "world"
        }, new ByteArrayInputStream(new byte[0]), writer(output), writer(error));

        assertEquals(0, exitCode);
        assertEquals("hello-world\n", output.toString(StandardCharsets.UTF_8));
        assertEquals("", error.toString(StandardCharsets.UTF_8));

        output.reset();
        assertEquals(0, FibraCli.run(new String[] {
            "--home", home.toString(), "help", "echo"
        }, new ByteArrayInputStream(new byte[0]), writer(output), writer(error)));
        var help = output.toString(StandardCharsets.UTF_8);
        assertTrue(help.contains("输出带前缀的参数。"), help);
        assertTrue(help.contains("--prefix"), help);
    }

    @Test
    void dynamicCommandPathConflictReturnsStableExecutionFailure(@TempDir Path home)
        throws Exception {
        dynamicCommandProfile(home);
        Files.writeString(home.resolve("config/profiles/default.yaml"),
            "- {id: command-a, plugin: command}\n- {id: command-b, plugin: command}\n");
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {
            "--home", home.toString(), "echo"
        }, new ByteArrayInputStream(new byte[0]), writer(output), writer(error));

        assertEquals(4, exitCode);
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertTrue(error.toString(StandardCharsets.UTF_8)
            .contains("duplicate CLI command path: echo"));
    }

    @Test
    void replCapturesANewCommandGenerationForEveryLine(@TempDir Path home) throws Exception {
        dynamicCommandProfile(home);
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {"--home", home.toString(), "repl"},
            new ByteArrayInputStream(("echo first\nplugins disable command\n"
                + "echo second\nexit\n").getBytes(StandardCharsets.UTF_8)),
            writer(output), writer(error));

        assertEquals(0, exitCode);
        assertEquals(1, output.toString(StandardCharsets.UTF_8).split("first", -1).length - 1);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("echo"));
    }

    @Test
    void nonTtyReplNeverGrantsADynamicTerminalLease(@TempDir Path home)
        throws Exception {
        dynamicCommandProfile(home);
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {"--home", home.toString(), "repl"},
            new ByteArrayInputStream("terminal\necho next\nexit\n".getBytes(StandardCharsets.UTF_8)),
            writer(output), writer(error));

        assertEquals(0, exitCode);
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("leased"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("next\n"));
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("UNSUPPORTED"));
    }

    @Test
    void nonTtyLeaseFailureDoesNotReplaceTheAdmittedDynamicGeneration(@TempDir Path home)
        throws Exception {
        dynamicCommandProfile(home);
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = FibraCli.run(new String[] {"--home", home.toString(), "repl"},
            new ByteArrayInputStream("plugins list\ninterrupt\nplugins list\necho next\nexit\n"
                .getBytes(StandardCharsets.UTF_8)), writer(output), writer(error));

        assertEquals(0, exitCode);
        var rendered = output.toString(StandardCharsets.UTF_8);
        assertFalse(rendered.contains("cancelled\n"), rendered);
        assertTrue(rendered.contains("next\n"), rendered);
        var snapshots = rendered.lines().filter(line -> line.contains("\"id\":\"command\""))
            .toList();
        assertEquals(2, snapshots.size(), rendered);
        assertEquals(snapshots.getFirst().substring(snapshots.getFirst().indexOf('{')),
            snapshots.getLast().substring(snapshots.getLast().indexOf('{')),
            "取消当前 invocation 不得替换插件实例、ClassLoader 或 effects");
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("UNSUPPORTED"));
    }

    @Test
    void nonTtyBootstrapLeaseFailureDoesNotConsumeFollowingCommands(@TempDir Path home)
        throws Exception {
        var profiles = Files.createDirectories(home.resolve("config/profiles"));
        Files.writeString(profiles.resolve("default.yaml"), "[]\n");
        Files.writeString(profiles.resolve("default.artifacts.yaml"), "[]\n");
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var application = CliApplication.builder("agent")
            .description("测试取消投影。").version("1")
            .addBootstrapCommand(new CliBootstrapCommand(new CliCommandDescriptor(
                List.of("read-key"), "等待受控终端输入。", List.of(), null, List.of()),
                request -> {
                    try (var lease = request.invocation().terminal().acquire()) {
                        lease.run(idleRenderer());
                        return CliCommandResult.success();
                    }
                }))
            .addBootstrapCommand(new CliBootstrapCommand(new CliCommandDescriptor(
                List.of("status"), "输出状态。", List.of(), null, List.of()), request -> {
                    request.invocation().output().stdout("ready");
                    return CliCommandResult.success();
                }))
            .addBootstrapCommand(new CliBootstrapCommand(new CliCommandDescriptor(
                List.of("fail-after-interrupt"), "中断后模拟清理失败。", List.of(), null, List.of()),
                request -> {
                    try (var lease = request.invocation().terminal().acquire()) {
                        lease.run(idleRenderer());
                        return CliCommandResult.success();
                    } catch (InterruptedIOException expected) {
                        throw new IllegalStateException("post-cancel cleanup failed");
                    }
                }))
            .addBootstrapCommand(new CliBootstrapCommand(new CliCommandDescriptor(
                List.of("fail-suppressed-after-interrupt"), "中断后模拟 suppressed 清理失败。",
                List.of(), null, List.of()), request -> {
                    AutoCloseable cleanup = () -> {
                        throw new IllegalStateException("suppressed cleanup failed");
                    };
                    try (cleanup; var lease = request.invocation().terminal().acquire()) {
                        lease.run(idleRenderer());
                        return CliCommandResult.success();
                    }
                }))
            .build();

        var exitCode = runApplication(application, new String[] {"repl"}, home,
            new ByteArrayInputStream(("read-key\nstatus\nfail-after-interrupt\nstatus\n"
                + "fail-suppressed-after-interrupt\nstatus\nexit\n")
                .getBytes(StandardCharsets.UTF_8)), writer(output), writer(error));

        assertEquals(0, exitCode);
        assertEquals(3, output.toString(StandardCharsets.UTF_8)
            .split("ready", -1).length - 1);
        var diagnostics = error.toString(StandardCharsets.UTF_8);
        assertTrue(diagnostics.contains("UNSUPPORTED"), diagnostics);
        assertFalse(diagnostics.contains("terminal input cancelled"), diagnostics);
    }

    @Test
    void replHistoryAndDiagnosticsRedactDynamicSensitiveOptions(@TempDir Path home) throws Exception {
        dynamicCommandProfile(home);
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var secret = "f2-dynamic-secret";

        assertEquals(0, FibraCli.run(new String[] {"--home", home.toString(), "repl"},
            new ByteArrayInputStream(("echo --secret " + secret + " visible\nexit\n")
                .getBytes(StandardCharsets.UTF_8)), writer(output), writer(error)));

        var persisted = Files.readString(home.resolve("data/profiles/default/repl.history"));
        assertTrue(persisted.contains("[敏感命令已省略]"), persisted);
        assertFalse(persisted.contains(secret), persisted);
        assertFalse(error.toString(StandardCharsets.UTF_8).contains(secret));
    }

    @Test
    void replHistoryRedactsBootstrapShortSensitiveOptions(@TempDir Path home) throws Exception {
        emptyProfile(home);
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var secret = "f2-bootstrap-short-secret";

        assertEquals(0, runApplication(sensitiveBootstrapApplication(),
            new String[] {"repl"}, home,
            new ByteArrayInputStream(("login -s" + secret + "\nexit\n")
                .getBytes(StandardCharsets.UTF_8)), writer(output), writer(error)));

        var persisted = Files.readString(home.resolve("data/profiles/default/repl.history"));
        assertTrue(persisted.contains("[敏感命令已省略]"), persisted);
        assertFalse(persisted.contains(secret), persisted);
        assertFalse(error.toString(StandardCharsets.UTF_8).contains(secret));
    }

    @Test
    void sensitiveBootstrapFailuresDoNotEchoTheSuppliedValue(@TempDir Path home) throws Exception {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var secret = "f2-bootstrap-diagnostic-secret";

        assertEquals(4, runApplication(sensitiveBootstrapApplication(),
            new String[] {"login", "-s" + secret}, home, new ByteArrayInputStream(new byte[0]),
            writer(output), writer(error)));

        var diagnostics = error.toString(StandardCharsets.UTF_8);
        assertTrue(diagnostics.contains("登录令牌无效: [敏感值已省略]"), diagnostics);
        assertFalse(diagnostics.contains(secret), diagnostics);
    }

    @Test
    void sensitiveBootstrapDashPrefixedValuesDoNotEchoTheSuppliedValue(@TempDir Path home)
        throws Exception {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var secret = "--f2-bootstrap-parse-secret";

        assertEquals(4, runApplication(sensitiveBootstrapApplication(),
            new String[] {"login", "--secret", secret}, home,
            new ByteArrayInputStream(new byte[0]), writer(output), writer(error)));

        var diagnostics = error.toString(StandardCharsets.UTF_8);
        assertTrue(diagnostics.contains("[敏感值已省略]"), diagnostics);
        assertFalse(diagnostics.contains(secret), diagnostics);
    }

    @Test
    void sensitiveBootstrapParseFailuresDoNotEchoTheSuppliedValue(@TempDir Path home)
        throws Exception {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var secret = "f2-bootstrap-parse-secret";

        assertEquals(2, runApplication(sensitiveBootstrapApplication(),
            new String[] {"login", "-s" + secret, "unexpected"}, home,
            new ByteArrayInputStream(new byte[0]), writer(output), writer(error)));

        var diagnostics = error.toString(StandardCharsets.UTF_8);
        assertTrue(diagnostics.contains("Unmatched argument at index 2: 'unexpected'"), diagnostics);
        assertFalse(diagnostics.contains(secret), diagnostics);
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

    private static int runApplication(CliApplication application, String[] arguments, Path home,
                                      ByteArrayInputStream input, PrintWriter output,
                                      PrintWriter error) throws Exception {
        emptyProfile(home);
        var paths = CliPaths.resolve(home, "default", null, null, null, null);
        var profile = new com.sstlfsj.fibra.cli.api.CliProfile(paths.profile(), paths.home(),
            paths.configRoot(), paths.pluginsRoot(), paths.dataRoot());
        try (var host = CliHost.open(paths);
             var session = CliSession.builder(application, host.published(), profile)
                 .streams(input, output, error).historyFile(paths.replHistoryFile()).build()) {
            return session.execute(arguments);
        }
    }

    private static CliApplication sensitiveBootstrapApplication() {
        return CliApplication.builder("agent")
            .addBootstrapCommand(new CliBootstrapCommand(
                new CliCommandDescriptor(List.of("login"), "登录。",
                    List.of(new CliCommandOption(List.of("-s", "--secret"), "令牌。",
                        true, true, List.of())), null, List.of()),
                request -> {
                    throw new IllegalStateException("登录令牌无效: "
                        + request.options().get("-s"));
                }))
            .build();
    }

    private static void emptyProfile(Path home) throws Exception {
        var profiles = Files.createDirectories(home.resolve("config/profiles"));
        Files.writeString(profiles.resolve("default.yaml"), "[]\n");
        Files.writeString(profiles.resolve("default.artifacts.yaml"), "[]\n");
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

    private static void dynamicCommandProfile(Path home) throws Exception {
        var profiles = Files.createDirectories(home.resolve("config/profiles"));
        Files.writeString(profiles.resolve("default.yaml"),
            "- {id: command, plugin: command}\n");
        Files.writeString(profiles.resolve("default.artifacts.yaml"), "- command\n");
        var root = Files.createDirectories(home.resolve("plugins/command"));
        var lib = Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("plugin.properties"), """
            formatVersion=1
            runtime=java
            payload=lib/main.jar
            """);
        try (var jar = new JarOutputStream(Files.newOutputStream(lib.resolve("main.jar")))) {
            jar.putNextEntry(new JarEntry("META-INF/fibra/plugin.yaml"));
            jar.write(("""
                id: command
                version: 1.0.0
                entrypoint: %s
                requires: []
                """).formatted(DynamicCommandEntrypoint.class.getName())
                .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            var className = DynamicCommandEntrypoint.class.getName().replace('.', '/') + ".class";
            jar.putNextEntry(new JarEntry(className));
            try (var input = FibraCliTest.class.getResourceAsStream('/' + className)) {
                if (input == null) throw new IllegalStateException("missing test entrypoint class");
                jar.write(input.readAllBytes());
            }
            jar.closeEntry();
        }
    }

    public static final class DynamicCommandEntrypoint implements PluginEntrypoint<Void> {
        @Override public PluginDefinition<Void> definition() {
            return PluginDefinition.builder("command", Void.class, () -> (context, config) -> {
                var provider = context.plugins().current().orElseThrow().id();
                var descriptor = new CliCommandDescriptor(List.of("echo"),
                    "输出带前缀的参数。", List.of(
                    new CliCommandOption(List.of("--prefix"), "输出前缀。", false, false,
                        List.of("hello-")),
                    new CliCommandOption(List.of("--secret"), "敏感测试参数。", false, true,
                        List.of())), "TEXT", List.of());
                var registrar = context.services().require(ContributionServices.REGISTRAR);
                var echo = registrar.register(context,
                    CliCommandContributions.KIND, provider, "echo", descriptor,
                    (invocation, request) -> {
                        if (request.options().containsKey("--secret")) {
                            throw new IllegalStateException("敏感参数无效: "
                                + request.options().get("--secret"));
                        }
                        var prefix = request.options().getOrDefault("--prefix", "");
                        request.invocation().output().stdout(prefix
                            + String.join(" ", request.arguments()));
                        return Mono.just(CliCommandResult.success());
                    });
                var terminal = registrar.register(context, CliCommandContributions.KIND,
                    provider, "terminal", new CliCommandDescriptor(List.of("terminal"),
                        "取得受控终端租约。", List.of(), null, List.of()),
                    (invocation, request) -> {
                        request.invocation().terminal().acquire();
                        request.invocation().output().stdout("leased");
                        return Mono.just(CliCommandResult.success());
                    });
                var interrupt = registrar.register(context, CliCommandContributions.KIND,
                    provider, "interrupt", new CliCommandDescriptor(List.of("interrupt"),
                        "等待受控终端中断。", List.of(), null, List.of()),
                    (invocation, request) -> {
                        try (var lease = request.invocation().terminal().acquire()) {
                            lease.run(idleRenderer());
                            return Mono.error(new IllegalStateException("expected terminal interrupt"));
                        } catch (InterruptedIOException expected) {
                            if (!request.invocation().cancellation().isCancelled()) {
                                return Mono.error(new IllegalStateException(
                                    "terminal and invocation cancellation were not coordinated"));
                            }
                            request.invocation().output().stdout("cancelled");
                            return Mono.error(expected);
                        } catch (Exception failure) {
                            return Mono.error(failure);
                        }
                    });
                return Mono.when(echo, terminal, interrupt);
            }).require(ContributionServices.REGISTRAR).build();
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
