package com.sstlfsj.fibra.cli.api;

import com.sstlfsj.fibra.CancellationToken;

import java.util.Objects;

public record CliInvocation(CancellationToken cancellation, CliOutput output,
                            CliTerminal terminal, CliProfile profile) {
    public CliInvocation {
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(profile, "profile");
    }
}
