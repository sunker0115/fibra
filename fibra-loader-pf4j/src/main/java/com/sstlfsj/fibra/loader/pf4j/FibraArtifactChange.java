package com.sstlfsj.fibra.loader.pf4j;

import java.util.List;

/** 已完成预检、可由上层事务协调的插件制品变更。 */
public interface FibraArtifactChange extends AutoCloseable {
    List<String> changedArtifactIds();

    FibraPluginCatalog targetCatalog();

    void commit();

    void complete();

    void rollback();

    @Override
    void close();
}
