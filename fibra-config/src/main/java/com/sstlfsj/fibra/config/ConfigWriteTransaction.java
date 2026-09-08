package com.sstlfsj.fibra.config;

public interface ConfigWriteTransaction extends AutoCloseable {
    ConfigDocumentSnapshot candidate();

    void commit();

    void rollback();

    @Override
    default void close() {
        rollback();
    }
}
