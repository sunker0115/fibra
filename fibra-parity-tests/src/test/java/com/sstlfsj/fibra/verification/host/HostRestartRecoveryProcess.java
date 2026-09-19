package com.sstlfsj.fibra.verification.host;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.artifact.ExecutionTarget;
import com.sstlfsj.fibra.artifact.FacetDependency;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.PluginPackageRecord;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKindRegistry;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.bridge.ContributionSnapshotEntry;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.config.PublicationRequirement;
import com.sstlfsj.fibra.engine.ApplyDeployment;
import com.sstlfsj.fibra.engine.BuiltInFacet;
import com.sstlfsj.fibra.engine.BuiltInPluginPackage;
import com.sstlfsj.fibra.engine.ExecutionObservation;
import com.sstlfsj.fibra.engine.ExecutionUnitKey;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.FileDeploymentTargetStore;
import com.sstlfsj.fibra.engine.PluginSelection;
import com.sstlfsj.fibra.engine.PublishedView;
import com.sstlfsj.fibra.engine.RemoteContributionInvoker;
import com.sstlfsj.fibra.runtime.java.JavaBuiltInPackage;
import com.sstlfsj.fibra.runtime.java.JavaDefinitionEntry;
import com.sstlfsj.fibra.runtime.java.JavaRuntimeProvider;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeOptions;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeProvider;
import com.sstlfsj.fibra.value.LiteralValue;
import com.sstlfsj.fibra.verification.external.ExternalFixtureRuntimeHarness;
import com.sstlfsj.fibra.verification.external.ExternalFixtureRuntimeProvider;
import verification.host.fixture.DynamicJavaEntrypoint;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** 独立 JVM 中运行的真实 Host A/Host B；由 HostRestartRecoveryTest 驱动。 */
public final class HostRestartRecoveryProcess {
    private static final String JAVA_PLUGIN = "restart-java";
    private static final String NODE_PLUGIN = "restart-node";
    private static final String EXTERNAL_PLUGIN = "restart-external";
    private static final String BUILT_IN_PLUGIN = "restart-built-in";
    private static final String JAVA_ENTRY = "java-entry";
    private static final String NODE_ENTRY = "node-entry";
    private static final String EXTERNAL_A = "external-a";
    private static final String EXTERNAL_B = "external-b";
    private static final String BUILT_IN_ENTRY = "built-in-entry";
    private static final String BUILT_IN_DEFINITION = "built-in";
    private static final String BUILT_IN_DIGEST = "b".repeat(64);
    private static final FacetId MAIN_FACET = new FacetId("main");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private HostRestartRecoveryProcess() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                "usage: <seed|recover|expect-failure> <work> <report> [scenario|seed-report]");
        }
        var mode = args[0];
        var work = Path.of(args[1]).toAbsolutePath();
        var report = Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(work);
        switch (mode) {
            case "seed" -> seed(work, report);
            case "recover" -> recover(work, report, Path.of(args[3]));
            case "expect-failure" -> expectFailure(work, report, args[3]);
            default -> throw new IllegalArgumentException("unknown mode " + mode);
        }
    }

    private static void seed(Path work, Path report) throws Exception {
        HostVerificationContract.reset();
        var packages = new PluginPackageStore(work.resolve("packages"));
        var javaPackage = install(packages,
            javaPackage(work.resolve("sources/java")));
        var nodePids = work.resolve("node-pids.txt").toAbsolutePath();
        var nodePackage = install(packages,
            nodePackage(work.resolve("sources/node"), nodePids));
        var externalPackage = install(packages,
            externalPackage(work.resolve("sources/external")));
        var external = new ExternalFixtureRuntimeHarness();
        var kinds = kinds();
        var marker = work.resolve("java-marker.txt").toAbsolutePath();
        var recoveryMarker = work.resolve("recovery-ready/java-marker.txt")
            .toAbsolutePath();
        var durableTarget = work.resolve("target/target.json").toAbsolutePath();
        var values = new Properties();

        try (var engine = FibraEngine.builder(packages,
                new FileDeploymentTargetStore(work.resolve("target")))
            .runtimeProvider(new JavaRuntimeProvider(List.of(
                builtIn(BUILT_IN_DIGEST, false))))
            .runtimeProvider(new NodeRuntimeProvider(nodeOptions(work)))
            .runtimeProvider(external.provider())
            .contributionKinds(kinds)
            .hostTerminationPort(ignored -> { })
            .lifecycleTimeout(TIMEOUT).build()) {
            external.controller().goOnline();
            engine.startAsync().block(TIMEOUT);
            var initial = engine.submit(ApplyDeployment.builder(
                    graph(durableTarget, marker, "java-a", "node-a"))
                .expectedRevision(0)
                .selections(selections(javaPackage, nodePackage,
                    externalPackage, BUILT_IN_DIGEST))
                .configContext(ConfigContextSnapshot.empty()).build())
                .block(TIMEOUT).view();
            await(() -> allActive(engine.published().current())
                && engine.published().current().contributions().entries().size() == 5,
                () -> describe(engine.published().current()));
            initial = engine.published().current();
            require("java-a:request".equals(invoke(engine, initial,
                HostVerificationContract.KIND_NAME, "java-echo", null)),
                "Host A Java contribution is not callable");
            require("node-a:request".equals(invoke(engine, initial,
                HostVerificationContract.KIND_NAME, "node-echo", null)),
                "Host A Node contribution is not callable");
            require("built-in:request".equals(invoke(engine, initial,
                HostVerificationContract.KIND_NAME, "built-in-echo", null)),
                "Host A built-in contribution is not callable");
            require("{\"mode\":\"one\"}:request".equals(invoke(engine,
                initial, ExternalFixtureRuntimeProvider.REMOTE_KIND_NAME,
                "echo", "{\"mode\":\"one\"}")),
                "Host A external contribution is not callable");
            require(EXTERNAL_A.equals(contribution(initial, "echo",
                    "{\"mode\":\"one\"}").id().providerInstanceId()),
                "Host A external contribution A did not use stable entry identity");
            require(EXTERNAL_B.equals(contribution(initial, "echo",
                    "{\"mode\":\"two\"}").id().providerInstanceId()),
                "Host A external contribution B did not use stable entry identity");
            recordView(values, "a", initial);
            recordContribution(values, "old", initial, "java-echo", null);
            var pids = readPids(nodePids);
            values.setProperty("a.node.supervisorPid", Long.toString(pids[0]));
            values.setProperty("a.node.payloadPid", Long.toString(pids[1]));
            values.setProperty("a.javaMarker", Files.readString(marker));
            values.setProperty("javaPackageLocation",
                javaPackage.location().toString());

            var failed = engine.submit(ApplyDeployment.builder(
                    graph(durableTarget, recoveryMarker,
                        "java-recovered", "node-recovered"))
                .expectedRevision(1)
                .selections(selections(javaPackage, nodePackage,
                    externalPackage, BUILT_IN_DIGEST))
                .configContext(ConfigContextSnapshot.empty()).build())
                .block(TIMEOUT).view();
            require(detail(failed, JAVA_ENTRY).state()
                    == ExecutionObservation.State.FAILED,
                "the durable recovery target did not retain its execution failure");
            require(failed.engine().target().orElseThrow().targetRevision() == 2,
                "the failed execution target was not saved");
            require(!failed.engineDiagnostics().targetSatisfied(),
                "the failed execution target was reported as satisfied");
            recordTarget(values, failed);
            values.setProperty("a.hostProcessPid",
                Long.toString(ProcessHandle.current().pid()));
        }
        write(report, values);
    }

    private static void recover(Path work, Path report, Path seedReport)
        throws Exception {
        HostVerificationContract.reset();
        var seed = read(seedReport);
        var external = new ExternalFixtureRuntimeHarness();
        var kinds = kinds();
        var values = new Properties();
        try (var engine = FibraEngine.builder(
                new PluginPackageStore(work.resolve("packages")),
                new FileDeploymentTargetStore(work.resolve("target")))
            .runtimeProvider(new JavaRuntimeProvider(List.of(
                builtIn(BUILT_IN_DIGEST, false))))
            .runtimeProvider(new NodeRuntimeProvider(nodeOptions(work)))
            .runtimeProvider(external.provider())
            .contributionKinds(kinds)
            .hostTerminationPort(ignored -> { })
            .lifecycleTimeout(TIMEOUT).build()) {
            external.controller().goOnline();
            engine.startAsync().block(TIMEOUT);
            await(() -> allActive(engine.published().current())
                && engine.published().current().contributions().entries().size() == 5,
                () -> describe(engine.published().current()));
            var current = engine.published().current();
            require(current.engineDiagnostics().targetSatisfied(),
                "Host B did not converge the durable target");
            require("java-recovered:request".equals(invoke(engine, current,
                HostVerificationContract.KIND_NAME, "java-echo", null)),
                "Host B Java contribution is not callable");
            require("node-recovered:request".equals(invoke(engine, current,
                HostVerificationContract.KIND_NAME, "node-echo", null)),
                "Host B Node contribution is not callable");
            require("built-in:request".equals(invoke(engine, current,
                HostVerificationContract.KIND_NAME, "built-in-echo", null)),
                "Host B built-in contribution is not callable");
            require("{\"mode\":\"one\"}:request".equals(invoke(engine,
                current, ExternalFixtureRuntimeProvider.REMOTE_KIND_NAME,
                "echo", "{\"mode\":\"one\"}")),
                "Host B external contribution A is not callable");
            require("{\"mode\":\"two\"}:request".equals(invoke(engine,
                current, ExternalFixtureRuntimeProvider.REMOTE_KIND_NAME,
                "echo", "{\"mode\":\"two\"}")),
                "Host B external contribution B is not callable");
            require(EXTERNAL_A.equals(contribution(current, "echo",
                    "{\"mode\":\"one\"}").id().providerInstanceId()),
                "Host B external contribution A did not preserve stable entry identity");
            require(EXTERNAL_B.equals(contribution(current, "echo",
                    "{\"mode\":\"two\"}").id().providerInstanceId()),
                "Host B external contribution B did not preserve stable entry identity");

            var oldId = new ContributionId(seed.getProperty("old.provider"),
                seed.getProperty("old.local"));
            var oldRegistration = Long.parseLong(
                seed.getProperty("old.registration"));
            require(rejected(engine, seed.getProperty("old.view"), oldId,
                    oldRegistration),
                "Host B accepted Host A's complete contribution tuple");
            var currentJava = contribution(current, "java-echo", null);
            require(currentJava.id().equals(oldId),
                "stable contribution business identity changed across Host restart");
            require(rejected(engine, current.viewRevision(), currentJava.id(),
                    Math.incrementExact(currentJava.registrationIdentity())),
                "Host B accepted a stale registration identity");

            recordView(values, "b", current);
            recordTarget(values, current);
            recordContribution(values, "current", current, "java-echo", null);
            var pids = readPids(work.resolve("node-pids.txt"));
            values.setProperty("b.node.supervisorPid", Long.toString(pids[0]));
            values.setProperty("b.node.payloadPid", Long.toString(pids[1]));
            values.setProperty("b.javaMarker", Files.readString(
                work.resolve("recovery-ready/java-marker.txt")));
            values.setProperty("oldTupleRejected", "true");
            values.setProperty("businessIdentityStable", "true");
            values.setProperty("registrationFenceRejected", "true");
            values.setProperty("b.hostProcessPid",
                Long.toString(ProcessHandle.current().pid()));
        }
        write(report, values);
    }

    private static void expectFailure(Path work, Path report, String scenario)
        throws Exception {
        var target = work.resolve("target/target.json");
        var before = Files.readAllBytes(target);
        Throwable failure = null;
        if ("metadata-definition-mismatch".equals(scenario)) {
            try {
                builtIn(BUILT_IN_DIGEST, true);
                failure = null;
            } catch (Throwable expected) {
                failure = expected;
            }
        } else {
            FibraEngine engine = null;
            try {
                var external = new ExternalFixtureRuntimeProvider();
                var builder = FibraEngine.builder(
                        new PluginPackageStore(work.resolve("packages")),
                        new FileDeploymentTargetStore(work.resolve("target")))
                    .runtimeProvider(new JavaRuntimeProvider(List.of(builtIn(
                        "old-built-in-digest".equals(scenario)
                            ? "c".repeat(64) : BUILT_IN_DIGEST, false))))
                    .runtimeProvider(new NodeRuntimeProvider(nodeOptions(work)))
                    .contributionKinds(kinds())
                    .hostTerminationPort(ignored -> { })
                    .lifecycleTimeout(TIMEOUT);
                if (!"missing-provider".equals(scenario)) {
                    builder.runtimeProvider(external);
                }
                engine = builder.build();
                engine.startAsync().block(TIMEOUT);
                failure = null;
            } catch (Throwable expected) {
                failure = expected;
            } finally {
                if (engine != null) {
                    try {
                        engine.close();
                    } catch (Throwable closeFailure) {
                        if (failure == null) {
                            failure = closeFailure;
                        } else {
                            failure.addSuppressed(closeFailure);
                        }
                    }
                }
            }
        }
        require(failure != null,
            "recovery unexpectedly succeeded for " + scenario);
        require(Arrays.equals(before, Files.readAllBytes(target)),
            "failed recovery rewrote the durable target for " + scenario);
        var values = new Properties();
        values.setProperty("scenario", scenario);
        values.setProperty("failure", failure.toString());
        values.setProperty("targetUnchanged", "true");
        write(report, values);
    }

    private static String invoke(FibraEngine engine, PublishedView view,
                                 String kind, String localName,
                                 String descriptor) {
        var contribution = contribution(view, localName, descriptor);
        var result = new RemoteContributionInvoker(kinds(),
            engine.published()).invoke(kind,
            contribution.id(), view.viewRevision(),
            contribution.registrationIdentity(), LiteralValue.of("request"))
            .block(TIMEOUT);
        if (!(result instanceof LiteralValue.StringValue text)) {
            throw new AssertionError("expected string contribution output: " + result);
        }
        return text.value();
    }

    private static boolean rejected(FibraEngine engine, String view,
                                    ContributionId id, long registration) {
        try {
            new RemoteContributionInvoker(kinds(), engine.published())
                .invoke(HostVerificationContract.KIND_NAME, id, view,
                    registration, LiteralValue.of("stale")).block(TIMEOUT);
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    private static ContributionKindRegistry kinds() {
        return ContributionKindRegistry.of(HostVerificationContract.ECHO_KIND,
            ExternalFixtureRuntimeProvider.REMOTE_KIND);
    }

    private static void recordView(Properties values, String prefix,
                                   PublishedView view) {
        values.setProperty(prefix + ".hostInstanceId",
            view.engine().hostInstanceId());
        values.setProperty(prefix + ".viewRevision", view.viewRevision());
        for (var entry : List.of(JAVA_ENTRY, NODE_ENTRY, EXTERNAL_A,
            EXTERNAL_B, BUILT_IN_ENTRY)) {
            var detail = detail(view, entry);
            values.setProperty(prefix + '.' + entry + ".runtimeInstanceId",
                detail.runtimeInstanceId());
            values.setProperty(prefix + '.' + entry + ".lifecycleOperationId",
                detail.lifecycleOperationId());
        }
    }

    private static void recordTarget(Properties values, PublishedView view) {
        var target = view.engine().target().orElseThrow();
        values.setProperty("targetRevision",
            Long.toString(target.targetRevision()));
        values.setProperty("targetDigest", target.targetDigest());
    }

    private static void recordContribution(Properties values, String prefix,
                                           PublishedView view,
                                           String localName,
                                           String descriptor) {
        var contribution = contribution(view, localName, descriptor);
        values.setProperty(prefix + ".view", view.viewRevision());
        values.setProperty(prefix + ".provider",
            contribution.id().providerInstanceId());
        values.setProperty(prefix + ".local", contribution.id().localName());
        values.setProperty(prefix + ".registration",
            Long.toString(contribution.registrationIdentity()));
    }

    private static ContributionSnapshotEntry contribution(PublishedView view,
                                                            String localName,
                                                            String descriptor) {
        var matches = view.contributions().entries().stream()
            .filter(value -> value.id().localName().equals(localName))
            .filter(value -> descriptor == null
                || descriptor.equals(value.descriptor())).toList();
        if (matches.size() != 1) {
            throw new AssertionError("expected one contribution " + localName
                + '/' + descriptor + ", got " + matches);
        }
        return matches.getFirst();
    }

    private static ExecutionObservation.Detail detail(PublishedView view,
                                                       String entry) {
        return view.engine().units().get(new ExecutionUnitKey(entry))
            .executions().getFirst();
    }

    private static boolean allActive(PublishedView view) {
        return List.of(JAVA_ENTRY, NODE_ENTRY, EXTERNAL_A, EXTERNAL_B,
                BUILT_IN_ENTRY).stream().allMatch(entry -> {
                    var observation = view.engine().units().get(
                        new ExecutionUnitKey(entry));
                    return observation != null && observation.aggregateState()
                        == ExecutionObservation.State.ACTIVE;
                });
    }

    private static DesiredInputGraph graph(Path target, Path marker,
                                           String javaPrefix,
                                           String nodePrefix) {
        return new DesiredInputGraph(List.of(
            DesiredInputEntry.builder(JAVA_ENTRY, new PluginDefinitionRef(
                    JAVA_PLUGIN, "main", "java"))
                .config(LiteralValue.of(Map.of("prefix", javaPrefix,
                    "startMarker", marker.toString(),
                    "targetPath", target.toString()))).build(),
            DesiredInputEntry.builder(NODE_ENTRY, new PluginDefinitionRef(
                    NODE_PLUGIN, "main", "node"))
                .config(LiteralValue.of(Map.of("prefix", nodePrefix,
                    "javaMarker", marker.toString(),
                    "targetPath", target.toString()))).build(),
            externalEntry(EXTERNAL_A, "one"),
            externalEntry(EXTERNAL_B, "two"),
            DesiredInputEntry.builder(BUILT_IN_ENTRY,
                    new PluginDefinitionRef(BUILT_IN_PLUGIN, "main",
                        BUILT_IN_DEFINITION))
                .config(LiteralValue.of(Map.of("prefix", "built-in")))
                .build()));
    }

    private static DesiredInputEntry externalEntry(String id, String mode) {
        return DesiredInputEntry.builder(id, new PluginDefinitionRef(
                EXTERNAL_PLUGIN, "main",
                ExternalFixtureRuntimeProvider.DEFINITION_ID))
            .publicationRequirement(PublicationRequirement.PENDING_ALLOWED)
            .config(LiteralValue.of(Map.of("mode", mode))).build();
    }

    private static List<PluginSelection> selections(
        PluginPackageRecord javaPackage, PluginPackageRecord nodePackage,
        PluginPackageRecord externalPackage, String builtInDigest) {
        return List.of(selection(javaPackage), selection(nodePackage),
            selection(externalPackage), new PluginSelection(
                new PluginId(BUILT_IN_PLUGIN), builtInDigest, true));
    }

    private static PluginSelection selection(PluginPackageRecord value) {
        return new PluginSelection(value.pluginId(), value.packageRevision(),
            true);
    }

    private static PluginPackageRecord install(PluginPackageStore store,
                                                Path source) {
        try (var transaction = store.prepareInstall(source)) {
            return transaction.save();
        }
    }

    private static JavaBuiltInPackage builtIn(String digest,
                                               boolean mismatch) {
        var definitions = mismatch
            ? Set.of(BUILT_IN_DEFINITION, "missing")
            : Set.of(BUILT_IN_DEFINITION);
        var metadata = BuiltInPluginPackage.builder()
            .pluginId(new PluginId(BUILT_IN_PLUGIN)).version("1.0.0")
            .packageDigest(digest)
            .facets(List.of(BuiltInFacet.builder(MAIN_FACET,
                    JavaRuntimeProvider.RUNTIME_ID,
                    new ExecutionTarget("host"))
                .definitionIds(definitions).build()))
            .build();
        var definition = PluginDefinition.builder(BUILT_IN_DEFINITION,
                Map.class, () -> (context, config) -> {
                    var prefix = String.valueOf(config.get("prefix"));
                    return context.services().require(
                            ContributionServices.REGISTRAR)
                        .register(context, HostVerificationContract.ECHO_KIND,
                            "built-in-echo",
                            "built-in-descriptor", (invocation, input) ->
                                reactor.core.publisher.Mono.just(
                                    prefix + ':' + input)).then();
                }).build();
        var entry = new JavaDefinitionEntry<>(definition,
            value -> (Map) value);
        return new JavaBuiltInPackage(metadata,
            Map.of(MAIN_FACET, List.of(entry)));
    }

    private static Path javaPackage(Path root) throws IOException {
        Files.createDirectories(root);
        var jar = root.resolve("plugin.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            jarEntry(output, "META-INF/fibra/plugin.yaml",
                "entrypoint: " + DynamicJavaEntrypoint.class.getName() + '\n');
            var className = DynamicJavaEntrypoint.class.getName()
                .replace('.', '/') + ".class";
            output.putNextEntry(new JarEntry(className));
            try (InputStream input = DynamicJavaEntrypoint.class
                .getResourceAsStream('/' + className)) {
                output.write(java.util.Objects.requireNonNull(input,
                    className).readAllBytes());
            }
            output.closeEntry();
        }
        Files.writeString(root.resolve("fibra-package.yaml"), packageManifest(
            JAVA_PLUGIN, "java", "plugin.jar", List.of()));
        return root;
    }

    private static Path nodePackage(Path root, Path pids) throws IOException {
        var payload = Files.createDirectories(root.resolve("node"));
        Files.writeString(payload.resolve("fibra-plugin.yaml"), """
            protocol: 1
            definitionId: node
            entrypoint: index.mjs
            contributions:
              - name: node-echo
                kind: fibra.verification.host.echo
                schemaVersion: 1
                method: host.echo
                descriptor: node-descriptor
            """);
        Files.writeString(payload.resolve("index.mjs"), nodeScript(pids));
        Files.writeString(root.resolve("fibra-package.yaml"), packageManifest(
            NODE_PLUGIN, "node", "node", List.of(new FacetDependency(
                new PluginId(JAVA_PLUGIN), MAIN_FACET))));
        return root;
    }

    private static Path externalPackage(Path root) throws IOException {
        var payload = Files.createDirectories(root.resolve("external"));
        Files.writeString(payload.resolve("fixture.txt"), "external fixture");
        Files.writeString(root.resolve("fibra-package.yaml"), packageManifest(
            EXTERNAL_PLUGIN,
            ExternalFixtureRuntimeProvider.RUNTIME_ID.value(), "external",
            List.of(new FacetDependency(new PluginId(NODE_PLUGIN),
                MAIN_FACET))));
        return root;
    }

    private static String packageManifest(String pluginId, String runtime,
                                          String payload,
                                          List<FacetDependency> dependencies) {
        var dependencyYaml = dependencies.isEmpty() ? "dependencies: []\n"
            : "dependencies:\n" + dependencies.stream().map(dependency ->
                "      - pluginId: " + dependency.pluginId().value() + "\n"
                    + "        facetId: " + dependency.facetId().value()
                    + "\n").collect(java.util.stream.Collectors.joining());
        return "format: 1\n"
            + "id: " + pluginId + "\n"
            + "version: 1.0.0\n"
            + "facets:\n"
            + "  - id: main\n"
            + "    role: host\n"
            + "    runtime: " + runtime + "\n"
            + "    target: host\n"
            + "    payload: " + payload + "\n"
            + "    " + dependencyYaml
            + "    capabilities: []\n";
    }

    private static String nodeScript(Path pids) {
        return """
            import fs from 'node:fs';
            import readline from 'node:readline';
            let config;
            const reply = (id, result) => process.stdout.write(
              JSON.stringify({jsonrpc:'2.0', id, result}) + '\\n');
            readline.createInterface({input: process.stdin}).on('line', line => {
              const message = JSON.parse(line);
              const {id, method, params} = message;
              if (method === 'fibra.handshake') reply(id, {protocol:1});
              else if (method === 'fibra.ping') reply(id, {ok:true});
              else if (method === 'fibra.start') {
                config = params.config;
                if (!fs.statSync(config.targetPath).isFile()) {
                  throw new Error('Node unit started before durable target was saved');
                }
                if (!fs.statSync(config.javaMarker).isFile()) {
                  throw new Error('Node unit started before its Java dependency');
                }
                fs.writeFileSync(%s, `${process.ppid}\\n${process.pid}\\n`);
                reply(id, {ok:true});
              }
              else if (method === 'host.echo') {
                reply(id, `${config.prefix}:${params.input}`);
              }
              else if (method === 'fibra.stop') reply(id, {ok:true});
            });
            """.formatted(quoted(pids.toAbsolutePath().toString()));
    }

    private static NodeRuntimeOptions nodeOptions(Path work) {
        return NodeRuntimeOptions.builder(
                Path.of(System.getProperty("fibra.test.node", "node")),
                work.resolve("node-sessions"))
            .handshakeTimeout(Duration.ofSeconds(3))
            .defaultRequestTimeout(Duration.ofSeconds(3))
            .terminateTimeout(Duration.ofSeconds(2)).build();
    }

    private static void jarEntry(JarOutputStream output, String name,
                                 String value) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\")
            .replace("\"", "\\\"") + '"';
    }

    private static long[] readPids(Path path) throws IOException {
        var lines = Files.readAllLines(path);
        require(lines.size() == 2, "invalid Node PID marker " + lines);
        return new long[]{Long.parseLong(lines.get(0)),
            Long.parseLong(lines.get(1))};
    }

    private static String describe(PublishedView view) {
        return "units=" + view.engine().units() + ", contributions="
            + view.contributions().entries() + ", failure="
            + view.engine().failure();
    }

    private static void await(BooleanSupplier condition,
                              Supplier<String> diagnostic) throws Exception {
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        require(condition.getAsBoolean(),
            "condition did not settle: " + diagnostic.get());
    }

    private static Properties read(Path path) throws IOException {
        var values = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            values.load(input);
        }
        return values;
    }

    private static void write(Path path, Properties values) throws IOException {
        Files.createDirectories(path.getParent());
        try (OutputStream output = Files.newOutputStream(path)) {
            values.store(output, null);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
