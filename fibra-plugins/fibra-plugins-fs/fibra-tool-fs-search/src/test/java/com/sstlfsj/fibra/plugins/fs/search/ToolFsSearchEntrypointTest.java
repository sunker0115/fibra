package com.sstlfsj.fibra.plugins.fs.search;

import com.sstlfsj.fibra.plugins.tool.ToolContent;

import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.bridge.ContributionDirectory;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.plugins.subprocess.ProcessUnit;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessOutcome;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessOutput;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessServices;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolFsSearchEntrypointTest {
    @TempDir
    Path temp;

    @Test
    void publishesBothToolsUnderTheActualPluginInstanceAndRevokesThemOnDispose()
        throws Exception {
        var executable = temp.resolve("rg");
        Files.writeString(executable, "placeholder");
        assertTrue(executable.toFile().setExecutable(true));
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var domain = runtime.openDomain("search");
            var context = domain.rootScope().context();
            context.services().provide(ContributionServices.REGISTRAR,
                directory.openAdmission("search-instance"));
            context.services().provide(SubprocessServices.SUBPROCESS,
                (invocation, spec) -> Mono.just(successUnit("one.txt\n")));
            var definition = new ToolFsSearchEntrypoint().definition();
            var plugin = context.plugins().mount("search-instance",
                definition.prepare(SearchPluginConfig.defaults(executable.toString(),
                    temp.toString())));

            assertEquals(PluginInstanceState.ACTIVE, plugin.settled().block().state());
            var snapshot = directory.current().snapshot();
            assertTrue(snapshot.entries().stream().anyMatch(entry ->
                entry.id().equals(ToolContributions.id("search-instance", "glob"))));
            assertTrue(snapshot.entries().stream().anyMatch(entry ->
                entry.id().equals(ToolContributions.id("search-instance", "grep"))));
            var result = directory.current().routes().invoke(context, ToolContributions.KIND,
                ToolContributions.id("search-instance", "glob"),
                ToolRequest.of(Map.of("pattern", "*"))).block();
            assertTrue(((ToolContent.Text) result.content().getFirst()).text().contains("one.txt"));

            plugin.dispose().block();
            assertFalse(directory.current().snapshot().entries().stream().anyMatch(entry ->
                entry.id().providerInstanceId().equals("search-instance")));
        }
    }

    @Test
    void rejectsAMissingRipgrepExecutableAtPluginStart() {
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var domain = runtime.openDomain("search");
            var context = domain.rootScope().context();
            context.services().provide(ContributionServices.REGISTRAR,
                directory.openAdmission("search-instance"));
            context.services().provide(SubprocessServices.SUBPROCESS,
                (invocation, spec) -> Mono.just(successUnit("")));
            var definition = new ToolFsSearchEntrypoint().definition();
            var plugin = context.plugins().mount("search-instance",
                definition.prepare(SearchPluginConfig.defaults(
                    temp.resolve("missing-rg").toString(), temp.toString())));

            plugin.settled().onErrorResume(error -> Mono.just(plugin)).block();
            assertEquals(PluginInstanceState.FAILED, plugin.state());
            assertTrue(plugin.failure().orElseThrow().getMessage().contains("rgExecutable"));
        }
    }

    @Test
    void runtimeDescriptorDeclaresOnlyTheEntrypoint() throws Exception {
        var manifest = Files.readString(Path.of("target/classes/META-INF/fibra/plugin.yaml"));
        assertEquals("entrypoint: " + ToolFsSearchEntrypoint.class.getName()
            + "\n", manifest);
    }

    private static ProcessUnit successUnit(String stdout) {
        var outcome = SubprocessOutcome.builder()
            .exitCode(0)
            .stdout(SubprocessOutput.builder().text(stdout).truncated(false)
                .totalBytes(stdout.length()).build())
            .stderr(SubprocessOutput.builder().text("").truncated(false).totalBytes(0).build())
            .build();
        return new ProcessUnit() {
            @Override public Mono<SubprocessOutcome> done() { return Mono.just(outcome); }
            @Override public void terminate() { }
            @Override public Mono<Void> waitForExit() { return Mono.empty(); }
            @Override public Mono<Void> drain() { return Mono.empty(); }
            @Override public Mono<Void> dispose() { return Mono.empty(); }
        };
    }
}
