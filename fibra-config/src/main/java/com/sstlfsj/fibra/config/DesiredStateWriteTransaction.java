package com.sstlfsj.fibra.config;

public interface DesiredStateWriteTransaction extends AutoCloseable {
    DesiredCompilation candidate();

    DesiredCompilation commit();

    void rollback();

    @Override
    default void close() {
        rollback();
    }
}
