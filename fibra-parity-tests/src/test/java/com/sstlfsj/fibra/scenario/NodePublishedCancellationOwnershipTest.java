package com.sstlfsj.fibra.scenario;

import com.sstlfsj.fibra.artifact.PluginPackageRecord;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionKindRegistry;
import com.sstlfsj.fibra.bridge.ContributionUnavailableException;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.engine.ApplyDeployment;
import com.sstlfsj.fibra.engine.AttemptPhase;
import com.sstlfsj.fibra.engine.DeploymentTargetStore;
import com.sstlfsj.fibra.engine.ExecutionUnitKey;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.PluginSelection;
import com.sstlfsj.fibra.engine.PublishedRevisionConflictException;
import com.sstlfsj.fibra.engine.PublishedView;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolContent;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeOptions;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeProvider;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodePublishedCancellationOwnershipTest {
    private static final String INSTANCE = "node-tool";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void cancellingOnePublishedToolCallDrainsItBeforeTheNodeInstanceCanStop(@TempDir Path work)
        throws Exception {
        var pidFile = work.resolve("node.pid");
        var holdEntered = work.resolve("hold-entered");
        var cancelObserved = work.resolve("cancel-observed");
        var lifecycleEvents = work.resolve("lifecycle-events");
        var release = work.resolve("release");
        var initial = graph(pidFile, holdEntered, cancelObserved, lifecycleEvents, release);
        var packages = new PluginPackageStore(work.resolve("packages"));
        var installed = install(packages, nodePackage(work));
        var selection = new PluginSelection(installed.pluginId(), installed.packageRevision(), true);

        try (var engine = engine(work, packages)) {
            engine.startAsync().block(TIMEOUT);
            var deployed = engine.submit(ApplyDeployment.builder(initial)
                .expectedRevision(0).selections(List.of(selection))
                .configContext(ConfigContextSnapshot.empty()).build()).block(TIMEOUT).view();
            var instanceIdentity = detail(deployed).runtimeInstanceId();
            var runId = toolId(deployed);
            var registrationIdentity = identity(deployed, ToolContributions.KIND, runId);
            var pid = recordedPid(pidFile);
            assertTrue(alive(pid));

            var cancelled = engine.published().invoke(deployed.viewRevision(), registrationIdentity,
                ToolContributions.KIND, runId, ToolRequest.of(Map.of("command", "hold")))
                .subscribe();
            try {
                awaitFile(holdEntered, "等待 Node 请求进入超时");

                var current = engine.published().current();
                var completed = engine.published().invoke(current.viewRevision(), identity(current,
                    ToolContributions.KIND, runId), ToolContributions.KIND, runId,
                    ToolRequest.of(Map.of("command", "complete"))).block(TIMEOUT);
                assertEquals(List.of(ToolContent.text("B completed")), completed.content());
                assertEquals(List.of(pid), recordedPids(pidFile));

                cancelled.dispose();
                awaitFile(cancelObserved, "取消订阅未传达至 Node");
                assertTrue(alive(pid));

                var draining = engine.published().views().filter(view ->
                    view.engineDiagnostics().phase() == AttemptPhase.DRAINING).next().toFuture();
                var stopping = engine.published().views().filter(view ->
                    view.engineDiagnostics().phase() == AttemptPhase.STOPPING).next().toFuture();
                var disabling = engine.submit(ApplyDeployment.builder(
                        initial.withEnabled(INSTANCE, false))
                    .expectedRevision(1).selections(List.of(selection))
                    .configContext(ConfigContextSnapshot.empty()).build()).toFuture();
                var drainingView = draining.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

                awaitToolAdmissionClosed(engine, runId, registrationIdentity);
                assertFalse(disabling.isDone(), "远端取消尚未终态时，实例停用不能完成");
                assertEquals(instanceIdentity, detail(drainingView).runtimeInstanceId());
                assertEquals(List.of(pid), recordedPids(pidFile));
                assertTrue(alive(pid), "取消排空期间不得终止 Node sidecar");
                assertThrows(TimeoutException.class,
                    () -> stopping.get(1, TimeUnit.SECONDS),
                    "远端请求终态前不得进入停止阶段");

                Files.writeString(release, "release");
                stopping.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                var disabled = disabling.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).view();
                assertFalse(disabled.engine().units().containsKey(new ExecutionUnitKey(INSTANCE)));
                assertFalse(alive(pid), "远端终态后停用必须完成并关闭 Node sidecar");
                assertEquals(List.of("request-settled", "stop"),
                    Files.readAllLines(lifecycleEvents),
                    "实例只能在远端请求真实终态之后接收 stop");
            } finally {
                cancelled.dispose();
                if (Files.notExists(release)) Files.writeString(release, "release");
            }
        }
        assertRecordedPidsStopped(pidFile);
    }

    private static FibraEngine engine(Path work, PluginPackageStore packages) {
        return FibraEngine.builder(packages, DeploymentTargetStore.inMemory())
            .runtimeProvider(new NodeRuntimeProvider(NodeRuntimeOptions.defaults(
                Path.of(System.getProperty("fibra.test.node", "node")),
                work.resolve("node-sessions"))))
            .contributionKinds(ContributionKindRegistry.of(ToolContributions.KIND))
            .hostTerminationPort(ignored -> { })
            .build();
    }

    private static DesiredInputGraph graph(Path pidFile, Path holdEntered,
                                           Path cancelObserved, Path lifecycleEvents,
                                           Path release) {
        return new DesiredInputGraph(List.of(DesiredInputEntry.builder(INSTANCE,
                new PluginDefinitionRef(INSTANCE, "main", INSTANCE))
            .config(LiteralValue.of(Map.of("pidFile", pidFile.toString(),
                "holdEntered", holdEntered.toString(), "cancelObserved", cancelObserved.toString(),
                "lifecycleEvents", lifecycleEvents.toString(), "release", release.toString())))
            .build()));
    }

    private static PluginPackageRecord install(PluginPackageStore store,
                                                Path source) {
        try (var transaction = store.prepareInstall(source)) {
            return transaction.save();
        }
    }

    private static Path nodePackage(Path work) throws Exception {
        var root = Files.createDirectory(work.resolve(INSTANCE));
        var payload = Files.createDirectory(root.resolve("payload"));
        Files.writeString(root.resolve("fibra-package.yaml"), """
            format: 1
            id: node-tool
            version: 1.0.0
            facets:
              - id: main
                role: host
                runtime: node
                target: host
                payload: payload
                dependencies: []
                capabilities: []
            """);
        Files.writeString(payload.resolve("fibra-plugin.yaml"), """
            protocol: 1
            definitionId: node-tool
            entrypoint: index.mjs
            contributions:
              - name: run
                kind: fibra.tool
                schemaVersion: 2
                method: tool.run
                descriptor:
                  displayName: Node tool
                  description: Node cancellation ownership scenario
                  inputSchema: { type: object }
                  outputSchema: { type: object }
            """);
        Files.writeString(payload.resolve("index.mjs"), """
            import fs from 'node:fs';
            import readline from 'node:readline';
            let config;
            let heldRequest;
            const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              const {id, method} = message;
              if (method === 'fibra.handshake') reply(id, {protocol:1});
              else if (method === 'fibra.ping') reply(id, {ok:true});
              else if (method === 'fibra.start') {
                config = message.params.config;
                fs.appendFileSync(config.pidFile, process.pid + '\\n');
                reply(id, {ok:true});
              } else if (method === 'fibra.stop') {
                fs.appendFileSync(config.lifecycleEvents, 'stop' + String.fromCharCode(10));
                reply(id, {ok:true});
              } else if (method === 'tool.run') {
                const command = message.params.input.arguments.command;
                if (command === 'hold') {
                  heldRequest = id;
                  fs.writeFileSync(config.holdEntered, 'entered');
                } else if (command === 'complete') {
                  reply(id, {content:[{type:'text', text:'B completed'}]});
                }
              } else if (method === '$/cancelRequest') {
                fs.writeFileSync(config.cancelObserved, 'cancelled');
                const waiting = setInterval(() => {
                  if (fs.existsSync(config.release)) {
                    clearInterval(waiting);
                    fs.appendFileSync(config.lifecycleEvents, 'request-settled' + String.fromCharCode(10));
                    reply(heldRequest, {content:[{type:'text', text:'A cancelled'}]});
                    heldRequest = undefined;
                  }
                }, 10);
              }
            });
            """);
        return root;
    }

    private static void awaitFile(Path expected, String message)
        throws InterruptedException {
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (Files.notExists(expected)) {
            if (System.nanoTime() >= deadline) throw new AssertionError(message);
            Thread.sleep(10);
        }
    }

    private static void awaitToolAdmissionClosed(FibraEngine engine, ContributionId id,
                                                  long registrationIdentity) throws Exception {
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            var current = engine.published().current();
            try {
                engine.published().invoke(current.viewRevision(), registrationIdentity,
                    ToolContributions.KIND, id,
                    ToolRequest.of(Map.of("command", "complete"))).block(TIMEOUT);
            } catch (ContributionUnavailableException failure) {
                return;
            } catch (PublishedRevisionConflictException failure) {
                continue;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("实例停用未进入 contribution 排空阶段");
    }

    private static com.sstlfsj.fibra.engine.ExecutionObservation.Detail detail(
        PublishedView view) {
        var key = new ExecutionUnitKey(INSTANCE);
        var observation = view.engine().units().get(key);
        if (observation == null) observation = view.engine().retiring().get(key);
        return observation
            .executions().getFirst();
    }

    private static long recordedPid(Path pidFile) throws Exception {
        return recordedPids(pidFile).getFirst();
    }

    private static long identity(PublishedView view, ContributionKind<?, ?, ?> kind,
                                 ContributionId id) {
        return view.contributions().entries().stream()
            .filter(entry -> entry.kind().equals(kind.name()) && entry.id().equals(id))
            .map(entry -> entry.registrationIdentity()).findFirst().orElseThrow();
    }

    private static ContributionId toolId(PublishedView view) {
        return view.contributions().entries().stream()
            .filter(entry -> entry.kind().equals(ToolContributions.KIND.name()))
            .filter(entry -> entry.id().localName().equals("run"))
            .map(entry -> entry.id()).findFirst().orElseThrow();
    }

    private static List<Long> recordedPids(Path pidFile) throws Exception {
        return Files.exists(pidFile)
            ? Files.readAllLines(pidFile).stream().map(Long::parseLong).toList()
            : List.of();
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void assertRecordedPidsStopped(Path pidFile) throws Exception {
        for (var pid : recordedPids(pidFile)) {
            assertFalse(alive(pid), "Node 进程仍在运行: " + pid);
        }
    }
}
