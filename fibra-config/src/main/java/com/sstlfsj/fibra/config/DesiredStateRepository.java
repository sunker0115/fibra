package com.sstlfsj.fibra.config;

public interface DesiredStateRepository {
    DesiredCompilation load(PluginDefinitionResolver resolver);

    default boolean writable() {
        return false;
    }

    default DesiredStateWriteTransaction prepareReplace(String expectedRevision,
                                                        DesiredGraph candidate) {
        throw new UnsupportedOperationException("desired state repository is read-only");
    }
}
