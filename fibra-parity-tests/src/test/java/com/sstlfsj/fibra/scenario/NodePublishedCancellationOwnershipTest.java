package com.sstlfsj.fibra.scenario;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.engine.ApplyDeployment;
import com.sstlfsj.fibra.engine.ChangePhase;
import com.sstlfsj.fibra.engine.DeploymentArtifact;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.ReplaceDesiredGraph;
import com.sstlfsj.fibra.bridge.ContributionUnavailableException;
import com.sstlfsj.fibra.engine.PublishedRevisionConflictException;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
import com.sstlfsj.fibra.runtime.node.NodePluginRuntimeAdapter;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeOptions;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

        try (var engine = engine(work)) {
            var started = engine.start().block(TIMEOUT);
            var deployed = engine.submit(ApplyDeployment.builder(initial)
                .expectedRevision(started.viewRevision())
                .expectedDesiredRevision(started.engine().desiredSource().revision())
                .artifacts(List.of(artifact(nodeArtifact(work)))).build()).block(TIMEOUT).view();
            var identity = deployed.engine().instances().get(INSTANCE).identity();
            var pid = recordedPid(pidFile);
            assertTrue(alive(pid));

            var cancelled = engine.published().invoke(deployed.viewRevision(), ToolContributions.KIND,
                ToolContributions.id(INSTANCE, "run"), ToolRequest.of(Map.of("command", "hold")))
                .subscribe();
            try {
                awaitFile(holdEntered, "等待 Node 请求进入超时");

                var completed = engine.published().invoke(engine.published().current().viewRevision(),
                    ToolContributions.KIND, ToolContributions.id(INSTANCE, "run"),
                    ToolRequest.of(Map.of("command", "complete"))).block(TIMEOUT);
                assertEquals("B completed", completed.text());
                assertEquals(List.of(pid), recordedPids(pidFile));

                cancelled.dispose();
                awaitFile(cancelObserved, "取消订阅未传达至 Node");
                assertTrue(alive(pid));

                var reconciling = engine.published().views().filter(view ->
                    view.engineDiagnostics().phase() == ChangePhase.RECONCILING
                        && !view.engine().desiredGraph().plugins().get(INSTANCE).enabled())
                    .next().toFuture();
                var retiring = engine.published().views().filter(view ->
                    view.engineDiagnostics().phase() == ChangePhase.RETIRING)
                    .next().toFuture();
                var disabling = engine.submit(new ReplaceDesiredGraph(
                    engine.published().current().viewRevision(),
                    deployed.engine().desiredSource().revision(), initial.withEnabled(INSTANCE, false)))
                    .toFuture();
                var draining = reconciling.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

                awaitToolAdmissionClosed(engine);
                assertFalse(disabling.isDone(), "远端取消尚未终态时，实例停用不能完成");
                assertEquals(identity, draining.engine().instances().get(INSTANCE).identity());
                assertEquals(List.of(pid), recordedPids(pidFile));
                assertTrue(alive(pid), "取消排空期间不得终止共享 Node sidecar");
                assertThrows(TimeoutException.class,
                    () -> retiring.get(1, TimeUnit.SECONDS),
                    "远端请求终态前不得进入旧实例退役阶段");

                Files.writeString(release, "release");
                retiring.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                var disabled = disabling.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).view();
                assertFalse(disabled.engine().instances().containsKey(INSTANCE));
                assertFalse(alive(pid), "远端终态后停用必须完成并关闭 Node sidecar");
                assertEquals(List.of("request-settled", "stop"),
                    Files.readAllLines(lifecycleEvents),
                    "实例只能在远端请求真实终态之后接收 stop");
            } finally {
                cancelled.dispose();
                if (Files.notExists(release)) {
                    Files.writeString(release, "release");
                }
            }
        }
        assertRecordedPidsStopped(pidFile);
    }

    private static FibraEngine engine(Path work) {
        return FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(new ArtifactStore(work.resolve("artifacts")))
            .runtimeAdapter(new NodePluginRuntimeAdapter(name -> "fibra.tool".equals(name)
                ? Optional.of(ToolContributions.KIND) : Optional.empty(), NodeRuntimeOptions.defaults(
                    Path.of(System.getProperty("fibra.test.node", "node")),
                    work.resolve("node-sessions"))))
            .build();
    }

    private static DesiredInputGraph graph(Path pidFile, Path holdEntered, Path cancelObserved,
                                           Path lifecycleEvents, Path release) {
        return new DesiredInputGraph(List.of(DesiredInputEntry.builder(INSTANCE, INSTANCE)
            .config(LiteralValue.of(Map.of("pidFile", pidFile.toString(),
                "holdEntered", holdEntered.toString(), "cancelObserved", cancelObserved.toString(),
                "lifecycleEvents", lifecycleEvents.toString(), "release", release.toString())))
            .build()));
    }

    private static DeploymentArtifact artifact(Path source) {
        return DeploymentArtifact.builder().artifactId(new ArtifactId(INSTANCE))
            .runtimeId(NodePluginRuntimeAdapter.RUNTIME_ID).version("1.0.0").source(source).build();
    }

    private static Path nodeArtifact(Path work) throws Exception {
        var root = Files.createDirectory(work.resolve(INSTANCE));
        var payload = Files.createDirectory(root.resolve("payload"));
        Files.writeString(root.resolve("plugin.properties"), """
            formatVersion=1
            runtime=node
            payload=payload
            """);
        Files.writeString(payload.resolve("fibra-plugin.yaml"), """
            id: node-tool
            version: 1.0.0
            protocol: 1
            entrypoint: index.mjs
            contributions:
              - name: run
                kind: fibra.tool
                schemaVersion: 1
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
                  reply(id, {text:'B completed', data:null});
                }
              } else if (method === '$/cancelRequest') {
                fs.writeFileSync(config.cancelObserved, 'cancelled');
                const waiting = setInterval(() => {
                  if (fs.existsSync(config.release)) {
                    clearInterval(waiting);
                    fs.appendFileSync(config.lifecycleEvents, 'request-settled' + String.fromCharCode(10));
                    reply(heldRequest, {text:'A cancelled', data:null});
                    heldRequest = undefined;
                  }
                }, 10);
              }
            });
            """);
        return root;
    }

    private static void awaitFile(Path expected, String message) throws InterruptedException {
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (Files.notExists(expected)) {
            if (System.nanoTime() >= deadline) throw new AssertionError(message);
            Thread.sleep(10);
        }
    }

    private static void awaitToolAdmissionClosed(FibraEngine engine) throws Exception {
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            var current = engine.published().current();
            try {
                engine.published().invoke(current.viewRevision(), ToolContributions.KIND,
                    ToolContributions.id(INSTANCE, "run"),
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

    private static long recordedPid(Path pidFile) throws Exception {
        return recordedPids(pidFile).getFirst();
    }

    private static List<Long> recordedPids(Path pidFile) throws Exception {
        return Files.exists(pidFile) ? Files.readAllLines(pidFile).stream().map(Long::parseLong).toList()
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
