package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.CancellationSource;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.cli.api.CliCommandContributions;
import com.sstlfsj.fibra.cli.api.CliCommandDescriptor;
import com.sstlfsj.fibra.cli.api.CliCommandOption;
import com.sstlfsj.fibra.cli.api.CliCommandRequest;
import com.sstlfsj.fibra.cli.api.CliCommandResult;
import com.sstlfsj.fibra.cli.api.CliInvocation;
import com.sstlfsj.fibra.cli.api.CliOutput;
import com.sstlfsj.fibra.cli.api.CliProfile;
import com.sstlfsj.fibra.cli.api.CliTerminal;
import com.sstlfsj.fibra.cli.api.CliTerminalLease;
import com.sstlfsj.fibra.cli.api.CliTerminalUnavailableException;
import com.sstlfsj.fibra.cli.api.CliTerminalUnavailableReason;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.PluginCatalog;
import com.sstlfsj.fibra.engine.PluginCatalogEntry;
import com.sstlfsj.fibra.engine.PublishedRevisionConflictException;
import com.sstlfsj.fibra.engine.ReplaceDesiredGraph;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolDescriptor;
import com.sstlfsj.fibra.plugins.tool.ToolResult;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandGenerationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void capturedDescriptorHelpAndCompletionStayFixedWhileStaleAdmissionCallsNoHandler(
        @TempDir Path work) {
        var oldCalls = new AtomicInteger();
        var newCalls = new AtomicInteger();
        var repository = new InMemoryDesiredStateRepository(graph("old"));
        try (var engine = FibraEngine.builder(repository)
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(
                definition(oldCalls, newCalls), value -> (String) value))).build()) {
            var first = engine.start().block(TIMEOUT);
            var oldGeneration = CommandGeneration.capture(engine.published());
            var oldCommand = oldGeneration.commands().getFirst();
            var output = new ByteArrayOutputStream();
            var line = commandLine(oldGeneration, output);
            var parsed = line.parseArgs("echo", "old-value");
            var notAdmitted = oldGeneration.invoke(oldCommand, request(work));

            var second = engine.submit(new ReplaceDesiredGraph(first.viewRevision(),
                first.engine().desiredSource().revision(), graph("new"))).block(TIMEOUT).view();
            var newGeneration = CommandGeneration.capture(engine.published());
            var newCommand = newGeneration.commands().getFirst();

            assertNotEquals(first.viewRevision(), second.viewRevision());
            assertNotEquals(oldCommand.registrationIdentity(), newCommand.registrationIdentity());
            assertEquals("old description", oldCommand.descriptor().description());
            assertTrue(parsed.subcommand().commandSpec().usageMessage().description()[0]
                .contains("old description"));
            assertTrue(line.getSubcommands().get("echo").getUsageMessage()
                .contains("old description"));
            var completer = new CliCommandCompleter();
            completer.commandLine(line);
            completer.commandGeneration(oldGeneration);
            var candidates = candidates(completer, "echo ");
            assertTrue(candidates.contains("old-value"), candidates.toString());
            assertTrue(!candidates.contains("new-value"), candidates.toString());
            var helpCandidates = candidates(completer, "help e");
            assertTrue(helpCandidates.contains("echo"), helpCandidates.toString());
            var optionCandidates = candidates(completer, "echo --");
            assertTrue(optionCandidates.contains("--old"), optionCandidates.toString());
            var optionValueCandidates = candidates(completer, "echo --old ");
            assertTrue(optionValueCandidates.contains("old-option"), optionValueCandidates.toString());
            var oldProviders = candidates(completer, "tools invoke ");
            assertTrue(oldProviders.contains("command"), oldProviders.toString());
            var oldTools = candidates(completer, "tools invoke command ");
            assertTrue(oldTools.contains("old-tool"), oldTools.toString());
            var highlighter = new CliCommandHighlighter();
            highlighter.commandLine(line);
            var highlighted = highlighter.highlight(null, "echo old-value");
            assertNotEquals(org.jline.utils.AttributedStyle.DEFAULT, highlighted.styleAt(0));
            assertThrows(PublishedRevisionConflictException.class, () ->
                notAdmitted.block(TIMEOUT));
            assertEquals(0, oldCalls.get());
            assertEquals(0, newCalls.get());

            assertEquals(CliCommandResult.success(),
                newGeneration.invoke(newCommand, request(work)).block(TIMEOUT));
            completer.commandLine(commandLine(newGeneration, output));
            completer.commandGeneration(newGeneration);
            var newCandidates = candidates(completer, "echo ");
            assertTrue(newCandidates.contains("new-value"), newCandidates.toString());
            assertTrue(!newCandidates.contains("old-value"), newCandidates.toString());
            var newOptionCandidates = candidates(completer, "echo --");
            assertTrue(newOptionCandidates.contains("--new"), newOptionCandidates.toString());
            assertTrue(!newOptionCandidates.contains("--old"), newOptionCandidates.toString());
            var newOptionValueCandidates = candidates(completer, "echo --new ");
            assertTrue(newOptionValueCandidates.contains("new-option"),
                newOptionValueCandidates.toString());
            var newTools = candidates(completer, "tools invoke command ");
            assertTrue(newTools.contains("new-tool"), newTools.toString());
            assertTrue(!newTools.contains("old-tool"), newTools.toString());
            assertEquals(0, oldCalls.get());
            assertEquals(1, newCalls.get());
        }
    }

    private static List<String> candidates(CliCommandCompleter completer, String line) {
        var parsed = new org.jline.reader.impl.DefaultParser().parse(line, line.length(),
            org.jline.reader.Parser.ParseContext.COMPLETE);
        var values = new ArrayList<org.jline.reader.Candidate>();
        completer.complete(null, parsed, values);
        return values.stream().map(org.jline.reader.Candidate::value).toList();
    }

    private static CommandLine commandLine(CommandGeneration generation,
                                           ByteArrayOutputStream output) {
        var root = new CommandLine(CommandLine.Model.CommandSpec.create().name("fibra"))
            .setOut(new PrintWriter(output, true, StandardCharsets.UTF_8));
        generation.install(root, (command, arguments, options) -> 0);
        root.addSubcommand("help", new CommandLine.HelpCommand());
        return root;
    }

    private static PluginDefinition<String> definition(AtomicInteger oldCalls,
                                                        AtomicInteger newCalls) {
        return PluginDefinition.builder("command", String.class, () -> (context, value) -> {
            var provider = context.plugins().current().orElseThrow();
            var descriptor = new CliCommandDescriptor(List.of("echo"),
                value + " description", List.of(new CliCommandOption(List.of("--" + value),
                value + " option", false, false, List.of(value + "-option"))), "VALUE",
                List.of(value + "-value"));
            var registrar = context.services().require(ContributionServices.REGISTRAR);
            var command = registrar.register(context, CliCommandContributions.KIND, provider.id(), "echo",
                    descriptor, (invocation, request) -> {
                        (value.equals("old") ? oldCalls : newCalls).incrementAndGet();
                        return Mono.just(CliCommandResult.success());
                    });
            var tool = registrar.register(context, ToolContributions.KIND, provider.id(), value + "-tool",
                new ToolDescriptor(value + " tool", "completion fixture",
                    (LiteralValue.ObjectValue) LiteralValue.of(Map.of("type", "object")),
                    (LiteralValue.ObjectValue) LiteralValue.of(Map.of("type", "object"))),
                (invocation, request) -> Mono.just(ToolResult.text(value)));
            return Mono.when(command, tool).then();
        }).require(ContributionServices.REGISTRAR).build();
    }

    private static DesiredInputGraph graph(String value) {
        return new DesiredInputGraph(List.of(DesiredInputEntry.builder("command", "command")
            .config(LiteralValue.of(value)).build()));
    }

    private static CliCommandRequest request(Path work) {
        var cancellation = new CancellationSource();
        return new CliCommandRequest(List.of(), Map.of(), new CliInvocation(cancellation.token(),
            new CliOutput() {
                @Override public void stdout(String value) { }
                @Override public void stderr(String value) { }
            }, new CliTerminal() {
                @Override public boolean interactive() { return false; }
                @Override public CliTerminalLease acquire() {
                    throw new CliTerminalUnavailableException(
                        CliTerminalUnavailableReason.UNSUPPORTED);
                }
            }, new CliProfile("test", work, work, work, work)));
    }
}
