package com.sstlfsj.fibra.cli.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record CliCommandDescriptor(List<String> path, String description,
                                   List<CliCommandOption> options,
                                   String argumentsLabel,
                                   List<String> argumentCompletions) {
    public CliCommandDescriptor {
        path = List.copyOf(path);
        if (path.isEmpty() || path.stream().anyMatch(part -> part == null
            || !part.matches("[a-z0-9][a-z0-9-]*"))) {
            throw new IllegalArgumentException("command path must use CLI command names");
        }
        description = text(description, "description");
        options = List.copyOf(options);
        var names = new HashSet<String>();
        for (var option : options) {
            Objects.requireNonNull(option, "option");
            if (option.names().stream().anyMatch(name -> !names.add(name))) {
                throw new IllegalArgumentException("duplicate command option name");
            }
        }
        if (argumentsLabel != null && argumentsLabel.isBlank()) {
            throw new IllegalArgumentException("argumentsLabel must not be blank");
        }
        argumentCompletions = List.copyOf(argumentCompletions);
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
