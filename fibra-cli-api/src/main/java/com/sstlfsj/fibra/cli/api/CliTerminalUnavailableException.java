package com.sstlfsj.fibra.cli.api;

import java.util.Objects;

public final class CliTerminalUnavailableException extends IllegalStateException {
    private final CliTerminalUnavailableReason reason;

    public CliTerminalUnavailableException(CliTerminalUnavailableReason reason) {
        super("CLI terminal is unavailable: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
    }

    public CliTerminalUnavailableReason reason() {
        return reason;
    }
}
