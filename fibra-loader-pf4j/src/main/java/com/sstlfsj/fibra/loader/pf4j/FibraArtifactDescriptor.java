package com.sstlfsj.fibra.loader.pf4j;

import java.util.Objects;

/** 不含插件对象或 ClassLoader 引用的插件制品身份。 */
public record FibraArtifactDescriptor(String id, String version, String sha256) {
    public FibraArtifactDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(sha256, "sha256");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hexadecimal digits");
        }
    }
}
