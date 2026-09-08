package com.sstlfsj.fibra.artifact;

public interface ArtifactInstallTransaction extends AutoCloseable {
    ArtifactRecord candidate();

    ArtifactRecord commit();

    ArtifactRecord quarantine(String reason);

    void rollback();

    @Override
    default void close() {
        rollback();
    }
}
