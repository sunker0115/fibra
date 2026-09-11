package com.sstlfsj.fibra.plugins.subprocess.local;

/** Node is an explicit supervisor runtime requirement, never a tool-side process seam. */
public record SubprocessLocalConfig(String nodeExecutable) {
    public SubprocessLocalConfig {
        if (nodeExecutable == null || nodeExecutable.isBlank()) {
            throw new IllegalArgumentException("nodeExecutable must not be blank");
        }
    }
}
