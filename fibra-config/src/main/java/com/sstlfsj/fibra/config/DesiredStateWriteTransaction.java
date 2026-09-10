package com.sstlfsj.fibra.config;

public interface DesiredStateWriteTransaction extends AutoCloseable {
    DesiredCompilation candidate();

    DesiredCompilation commit();

    /**
     * Restores the previous state, including after participant commit when the
     * enclosing Engine transaction has not reached its durable commit point.
     * Implementations must reject compensation that would overwrite a later write.
     */
    void rollback();

    @Override
    default void close() {
        rollback();
    }
}
