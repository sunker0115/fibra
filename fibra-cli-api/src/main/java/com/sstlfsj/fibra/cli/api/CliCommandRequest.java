package com.sstlfsj.fibra.cli.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CliCommandRequest(List<String> arguments, Map<String, String> options,
                                CliInvocation invocation) {
    public CliCommandRequest {
        arguments = List.copyOf(arguments);
        options = Map.copyOf(options);
        Objects.requireNonNull(invocation, "invocation");
    }
}
