package com.sstlfsj.fibra.plugins.shell.local;

public record ShellLocalConfig(String bashExecutable, int outputMaxBytes, long graceMillis) {
    public ShellLocalConfig {
        if (bashExecutable == null || bashExecutable.isBlank()) throw new IllegalArgumentException("bashExecutable must not be blank");
        if (outputMaxBytes <= 0 || graceMillis <= 0) throw new IllegalArgumentException("outputMaxBytes and graceMillis must be positive");
    }
}
