package com.sstlfsj.fibra.cli.api;

import com.sstlfsj.fibra.cli.api.command.CliBootstrapCommand;
import com.sstlfsj.fibra.cli.api.command.CliCommandContributions;
import com.sstlfsj.fibra.cli.api.command.CliCommandDescriptor;
import com.sstlfsj.fibra.cli.api.command.CliCommandOption;
import com.sstlfsj.fibra.cli.api.command.CliCommandResult;
import com.sstlfsj.fibra.cli.api.input.CliInputResult;
import com.sstlfsj.fibra.cli.api.invocation.CliExitStatus;
import com.sstlfsj.fibra.cli.api.terminal.CliTerminalCursor;
import com.sstlfsj.fibra.cli.api.terminal.CliTerminalFrame;
import com.sstlfsj.fibra.cli.api.terminal.CliTerminalInput;
import com.sstlfsj.fibra.cli.api.terminal.CliTerminalKey;
import com.sstlfsj.fibra.cli.api.terminal.CliTerminalModifier;
import com.sstlfsj.fibra.cli.api.terminal.CliTerminalSize;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliApiContractTest {
    @Test
    void commandFactsAreImmutableAndRejectInvalidNames() {
        var completions = new ArrayList<>(List.of("one"));
        var option = new CliCommandOption(List.of("--mode"), "执行模式。", true, false,
            completions);
        var descriptor = new CliCommandDescriptor(List.of("sample", "run"), "运行示例。",
            List.of(option), "ARG", List.of("first"));

        completions.add("two");

        assertEquals(List.of("one"), descriptor.options().getFirst().completions());
        assertThrows(UnsupportedOperationException.class,
            () -> descriptor.path().add("mutable"));
        assertThrows(IllegalArgumentException.class, () ->
            new CliCommandDescriptor(List.of("Bad Name"), "invalid", List.of(), null,
                List.of()));
    }

    @Test
    void applicationBuilderKeepsBootstrapCommandsOutOfTheDynamicRegistry() {
        var descriptor = new CliCommandDescriptor(List.of("status"), "显示状态。", List.of(),
            null, List.of());
        var command = new CliBootstrapCommand(descriptor,
            request -> CliCommandResult.success());

        var application = CliApplication.builder("product")
            .description("产品入口。")
            .version("1.0")
            .addBootstrapCommand(command)
            .build();

        assertEquals("product", application.rootName());
        assertEquals(List.of(command), application.bootstrapCommands());
        assertTrue(application.inputHandler().isEmpty());
        assertEquals("fibra.cli.command", CliCommandContributions.KIND.name());
        assertFalse(CliCommandContributions.KIND.codec().isPresent());
        assertEquals(0, CliCommandResult.success().status().code());
        assertEquals(CliInputResult.continueWith(CliCommandResult.success()),
            new CliInputResult(CliCommandResult.success(), false));
        assertTrue(CliInputResult.exitWith(CliCommandResult.success()).exitRequested());
    }

    @Test
    void drainTimeoutHasAStableExitCodeDistinctFromCloseFailureAndCancellation() {
        assertEquals(8, CliExitStatus.DRAIN_TIMEOUT.code());
        assertEquals(7, CliExitStatus.CLOSE_ERROR.code());
        assertEquals(130, CliExitStatus.CANCELLED.code());
    }

    @Test
    void terminalRendererFactsAreImmutableAndValidated() {
        var lines = new ArrayList<>(List.of("first", "second"));
        var cursor = new CliTerminalCursor(1, 2);
        var frame = new CliTerminalFrame(lines, Optional.of(cursor));

        lines.add("mutable");

        assertEquals(List.of("first", "second"), frame.lines());
        assertEquals(Optional.of(cursor), frame.cursor());
        assertEquals(new CliTerminalSize(80, 24), new CliTerminalSize(80, 24));
        assertThrows(IllegalArgumentException.class, () -> new CliTerminalSize(0, 24));
        assertThrows(IllegalArgumentException.class, () -> new CliTerminalCursor(-1, 0));
        assertThrows(IllegalArgumentException.class, () ->
            CliTerminalInput.key(CliTerminalKey.CHARACTER, "", Set.of()));
        assertEquals("pasted\ntext", CliTerminalInput.paste("pasted\ntext").text());
        assertTrue(CliTerminalInput.paste("pasted\ntext").isPaste());
        assertEquals(List.of("\033[31mred\033[0m"),
            new CliTerminalFrame(List.of("\033[31mred\033[0m"), Optional.empty()).lines());
        assertThrows(IllegalArgumentException.class, () ->
            new CliTerminalFrame(List.of("tab\ttext"), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () ->
            new CliTerminalFrame(List.of("bell\u0007"), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () ->
            new CliTerminalFrame(List.of("\033[2Jclear"), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () ->
            new CliTerminalFrame(List.of("\033]8;;https://example.com\u0007link"),
                Optional.empty()));
        assertFalse(List.of(CliTerminalKey.values()).stream()
            .anyMatch(value -> value.name().equals("UNKNOWN")));
        assertTrue(List.of(CliTerminalKey.values()).contains(CliTerminalKey.EOF));
        assertEquals(Set.of(CliTerminalModifier.SHIFT, CliTerminalModifier.CONTROL),
            Set.of(CliTerminalModifier.values()));
    }
}
