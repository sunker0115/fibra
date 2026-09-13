package com.sstlfsj.fibra.cli.api;

import java.util.Objects;

public record CliBootstrapCommand(CliCommandDescriptor descriptor,
                                  CliCommandHandler handler) {
    public CliBootstrapCommand {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(handler, "handler");
    }
}
