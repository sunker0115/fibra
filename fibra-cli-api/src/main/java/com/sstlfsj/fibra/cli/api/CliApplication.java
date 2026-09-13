package com.sstlfsj.fibra.cli.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CliApplication {
    private final String rootName;
    private final String description;
    private final String version;
    private final List<CliBootstrapCommand> bootstrapCommands;

    private CliApplication(Builder builder) {
        rootName = text(builder.rootName, "rootName");
        description = text(builder.description, "description");
        version = text(builder.version, "version");
        bootstrapCommands = List.copyOf(builder.bootstrapCommands);
    }

    public static Builder builder(String rootName) {
        return new Builder(rootName);
    }

    public String rootName() {
        return rootName;
    }

    public String description() {
        return description;
    }

    public String version() {
        return version;
    }

    public List<CliBootstrapCommand> bootstrapCommands() {
        return bootstrapCommands;
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    public static final class Builder {
        private final String rootName;
        private String description = "Fibra CLI application.";
        private String version = "unknown";
        private final List<CliBootstrapCommand> bootstrapCommands = new ArrayList<>();

        private Builder(String rootName) {
            this.rootName = rootName;
        }

        public Builder description(String value) {
            description = value;
            return this;
        }

        public Builder version(String value) {
            version = value;
            return this;
        }

        public Builder addBootstrapCommand(CliBootstrapCommand command) {
            bootstrapCommands.add(Objects.requireNonNull(command, "command"));
            return this;
        }

        public CliApplication build() {
            return new CliApplication(this);
        }
    }
}
