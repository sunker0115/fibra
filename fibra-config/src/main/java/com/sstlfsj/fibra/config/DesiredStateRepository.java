package com.sstlfsj.fibra.config;

public interface DesiredStateRepository {
    DesiredCompilation load();

    default boolean writable() {
        return false;
    }

    default DesiredStateWriteTransaction prepareReplace(String expectedRevision,
                                                        DesiredInputGraph candidate) {
        throw new UnsupportedOperationException("desired state repository is read-only");
    }
}
