package com.sstlfsj.fibra.cli.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals("fibra.cli.command", CliCommandContributions.KIND.name());
        assertFalse(CliCommandContributions.KIND.codec().isPresent());
        assertEquals(0, CliCommandResult.success().status().code());
    }
}
