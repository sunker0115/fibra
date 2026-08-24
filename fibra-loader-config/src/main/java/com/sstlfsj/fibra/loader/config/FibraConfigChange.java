package com.sstlfsj.fibra.loader.config;

/** 已完成预检、可被上层事务协调的配置变更。 */
public interface FibraConfigChange extends AutoCloseable {
    FibraConfigSnapshot targetSnapshot();

    void commit();

    void complete();

    void rollback();

    @Override
    void close();
}
