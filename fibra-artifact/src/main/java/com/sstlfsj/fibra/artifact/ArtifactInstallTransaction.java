package com.sstlfsj.fibra.artifact;

public interface ArtifactInstallTransaction extends AutoCloseable {
    ArtifactRecord candidate();

    /** 不可变保存指定 revision，不更新该 artifact 的 current 指针。 */
    ArtifactRecord save();

    void rollback();

    @Override
    default void close() {
        rollback();
    }
}
