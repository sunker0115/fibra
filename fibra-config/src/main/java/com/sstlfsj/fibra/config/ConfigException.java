package com.sstlfsj.fibra.config;

import java.util.Objects;

public final class ConfigException extends IllegalArgumentException {
    private final ConfigDiagnostic diagnostic;

    ConfigException(ConfigDiagnostic diagnostic, Throwable cause) {
        super(diagnostic.message(), cause);
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    public ConfigDiagnostic diagnostic() {
        return diagnostic;
    }
}
