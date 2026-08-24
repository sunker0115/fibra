package com.sstlfsj.fibra.engine;

import java.util.Objects;

record RevisionArtifact(String id, String version, String sha256) {
    RevisionArtifact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(sha256, "sha256");
    }
}
