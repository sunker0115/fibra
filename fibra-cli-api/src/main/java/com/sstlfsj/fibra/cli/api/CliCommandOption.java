package com.sstlfsj.fibra.cli.api;

import java.util.List;
import java.util.Objects;

public record CliCommandOption(List<String> names, String description, boolean required,
                               boolean sensitive, List<String> completions) {
    public CliCommandOption {
        names = List.copyOf(names);
        if (names.isEmpty() || names.stream().anyMatch(name -> name == null
            || !name.matches("--?[a-z0-9][a-z0-9-]*"))) {
            throw new IllegalArgumentException("option names must use CLI option syntax");
        }
        description = Objects.requireNonNull(description, "description");
        completions = List.copyOf(completions);
    }
}
