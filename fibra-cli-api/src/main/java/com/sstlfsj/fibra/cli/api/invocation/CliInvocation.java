package com.sstlfsj.fibra.cli.api.invocation;

import com.sstlfsj.fibra.cli.api.terminal.CliTerminal;

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
