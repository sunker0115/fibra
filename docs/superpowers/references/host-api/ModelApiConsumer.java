import com.sstlfsj.fibra.CancellationToken;
import com.sstlfsj.fibra.artifact.*;
import com.sstlfsj.fibra.bridge.*;
import com.sstlfsj.fibra.config.*;
import com.sstlfsj.fibra.engine.*;
import com.sstlfsj.fibra.engine.deployment.*;
import com.sstlfsj.fibra.engine.publication.*;
import com.sstlfsj.fibra.runtime.java.*;
import com.sstlfsj.fibra.value.LiteralValue;
import com.sstlfsj.tela.model.api.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

public final class ModelApiConsumer {
    private static final Duration LIMIT = Duration.ofSeconds(10);
    private static final List<Class<?>> API = List.of(
        com.sstlfsj.tela.model.api.ModelContinuation.class,
        com.sstlfsj.tela.model.api.ModelContributions.class,
        com.sstlfsj.tela.model.api.ModelDescriptor.class,
        com.sstlfsj.tela.model.api.ModelFailure.class,
        com.sstlfsj.tela.model.api.ModelFailure.Category.class,
        com.sstlfsj.tela.model.api.ModelFailure.ModelFailureBuilder.class,
        com.sstlfsj.tela.model.api.ModelProviderException.class,
        com.sstlfsj.tela.model.api.ModelRequest.class,
        com.sstlfsj.tela.model.api.ModelRequest.Initial.class,
        com.sstlfsj.tela.model.api.ModelRequest.Input.class,
        com.sstlfsj.tela.model.api.ModelRequest.ToolResults.class,
        com.sstlfsj.tela.model.api.ModelResponse.class,
        com.sstlfsj.tela.model.api.ModelResponse.AssistantText.class,
        com.sstlfsj.tela.model.api.ModelResponse.Completed.class,
        com.sstlfsj.tela.model.api.ModelResponse.Completion.class,
        com.sstlfsj.tela.model.api.ModelResponse.Incomplete.class,
        com.sstlfsj.tela.model.api.ModelResponse.ModelResponseBuilder.class,
        com.sstlfsj.tela.model.api.ModelResponse.OutputItem.class,
        com.sstlfsj.tela.model.api.ModelResponse.RequiresAction.class,
        com.sstlfsj.tela.model.api.ModelResponse.ToolCall.class,
        com.sstlfsj.tela.model.api.ModelResponse.Usage.class,
        com.sstlfsj.tela.model.api.ToolDefinition.class,
        com.sstlfsj.tela.model.api.ToolDefinition.ToolDefinitionBuilder.class,
        com.sstlfsj.tela.model.api.ToolExecutionResult.class,
        com.sstlfsj.tela.model.api.ToolExecutionResult.Status.class,
        com.sstlfsj.tela.model.api.ToolExecutionResult.ToolExecutionResultBuilder.class
    );
    public static void main(String[] args) throws Exception {
        var root = Path.of(args[0]);
        var packages = new PluginPackageStore(root.resolve("store"));
        var one = install(packages, root.resolve("packages/model-one"));
        var two = install(packages, root.resolve("packages/model-two"));
        var replacement = install(packages, root.resolve("packages/model-one-v2"));
        var provider = new JavaRuntimeProvider(List.of(), List.of(
            new JavaApiContract("qualification.model", "0.1.0-SNAPSHOT", API)));
        try (var engine = FibraEngine.builder(packages, DeploymentTargetStore.inMemory())
            .runtimeProvider(provider).hostTerminationPort(value -> { throw new AssertionError(value); }).build()) {
            engine.startAsync().block(LIMIT);
            var view = deploy(engine, 0, List.of(one, two));
            check(view.contributions().entries().size() == 2, "both providers published");
            call(engine, view, "model-one");
            call(engine, view, "model-two");
            System.out.println("HOST_TYPED_BOTH=true");
            System.out.println("API_INVENTORY_CLASSES=" + API.size());
            check(view.engine().target().orElseThrow().desiredGraph().plugins().size() == 2, "no API unit");
            System.out.println("NO_API_UNIT=true");
            var old = view;
            var oldRegistration = entry(view, "model-one").registrationIdentity();
            view = deploy(engine, 1, List.of(replacement, two));
            call(engine, view, "model-one");
            call(engine, view, "model-two");
            try { call(engine, old, "model-one"); throw new AssertionError("old view accepted"); }
            catch (PublishedRevisionConflictException expected) { }
            try {
                engine.published().invoke(
                    new ContributionHandle(view.viewRevision(), oldRegistration,
                        new ContributionId("model-one", "complete")),
                    ModelContributions.KIND, request()).block(LIMIT);
                throw new AssertionError("old registration accepted");
            } catch (ContributionUnavailableException expected) { }
            System.out.println("AFTER_REPLACE_TYPED_BOTH=true");
            view = deploy(engine, 2, List.of(two));
            call(engine, view, "model-two");
            check(view.contributions().entries().size() == 1, "peer survives removal");
            System.out.println("AFTER_REMOVE_TYPED_PEER=true");
            engine.closeAsync().block(LIMIT);
            engine.closeAsync().block(LIMIT);
            check(engine.published().current().contributions().entries().isEmpty(), "closed directory");
            check(new ModelDescriptor("host", "still alive").providerId().equals("host"), "Host API survives");
            System.out.println("AFTER_CLOSE_ENTRIES=0");
        }
    }
    private static ModelRequest request() {
        return new ModelRequest(new ModelRequest.Initial("probe"), List.of(), CancellationToken.never());
    }
    private static void call(FibraEngine engine, PublishedView view, String name) {
        var item = entry(view, name);
        check(item.descriptor() instanceof ModelDescriptor, "descriptor Host identity");
        var result = engine.published().invoke(
            new ContributionHandle(view.viewRevision(), item.registrationIdentity(), item.id()),
            ModelContributions.KIND, request()).block(LIMIT);
        check(result instanceof ModelResponse, "response Host identity");
        check(result.completion() instanceof ModelResponse.RequiresAction, "real provider requests action");
        var toolCall = (ModelResponse.ToolCall) result.output().getFirst();
        check(toolCall.toolId().localName().equals("read"), "real provider requests read");
        var toolResult = ToolExecutionResult.builder().callId(toolCall.callId())
            .status(ToolExecutionResult.Status.SUCCEEDED).text("host-result")
            .structured(Optional.of(LiteralValue.of(Map.of("lines", List.of(
                Map.of("number", 1, "text", "host-result")))))).build();
        var continued = new ModelRequest(new ModelRequest.ToolResults(
            result.continuation().orElseThrow(), List.of(toolResult)), List.of(), CancellationToken.never());
        var completed = engine.published().invoke(
            new ContributionHandle(view.viewRevision(), item.registrationIdentity(), item.id()),
            ModelContributions.KIND, continued).block(LIMIT);
        check(completed.completion() instanceof ModelResponse.Completed, "completed real model call");
        check(((ModelResponse.AssistantText) completed.output().getFirst()).text().equals("host-result\n"),
            "Host tool result returned through real provider");
    }
    private static ContributionSnapshotEntry entry(PublishedView view, String name) {
        return view.contributions().entries().stream().filter(value -> value.id().equals(
            new ContributionId(name, "complete"))).findFirst().orElseThrow();
    }
    private static PublishedView deploy(FibraEngine engine, long revision, List<PluginPackageRecord> records) {
        var graph = new DesiredInputGraph(records.stream().map(record -> DesiredInputEntry.builder(
            record.pluginId().value(), new PluginDefinitionRef(record.pluginId().value(), "main", "tela-model-deterministic"))
            .config(LiteralValue.of(Map.of())).build()).toList());
        return engine.submit(ApplyDeployment.builder(graph).expectedRevision(revision).selections(records.stream()
            .map(record -> new PluginSelection(record.pluginId(), record.packageRevision(), true)).toList())
            .configContext(ConfigContextSnapshot.empty()).build()).block(LIMIT).view();
    }
    private static PluginPackageRecord install(PluginPackageStore store, Path source) {
        try (var transaction = store.prepareInstall(source)) { return transaction.save(); }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
