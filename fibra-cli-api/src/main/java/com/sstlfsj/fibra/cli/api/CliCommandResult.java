package com.sstlfsj.fibra.cli.api;

import java.util.Objects;

public record CliCommandResult(CliExitStatus status) {
    public CliCommandResult {
        Objects.requireNonNull(status, "status");
    }

    public static CliCommandResult success() {
        return new CliCommandResult(CliExitStatus.SUCCESS);
    }
}
