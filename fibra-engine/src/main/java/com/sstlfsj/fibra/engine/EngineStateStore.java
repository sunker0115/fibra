package com.sstlfsj.fibra.engine;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** 唯一完整部署目标的存储；保存成功不表示运行态已经达成。 */
public interface EngineStateStore extends AutoCloseable {
    Optional<DeploymentManifest> load();

    /** 替换结果未确认时必须停止后续变更，不能通过反写旧目标补偿。 */
    void save(DeploymentManifest manifest);

    @Override
    default void close() { }

    static EngineStateStore inMemory() {
        return new EngineStateStore() {
            private DeploymentManifest target;
            private boolean closed;

            @Override
            public synchronized Optional<DeploymentManifest> load() {
                ensureOpen();
                return Optional.ofNullable(target);
            }

            @Override
            public synchronized void save(DeploymentManifest manifest) {
                ensureOpen();
                target = Objects.requireNonNull(manifest, "manifest");
            }

            @Override
            public synchronized void close() { closed = true; }

            private void ensureOpen() {
                if (closed) throw new IllegalStateException("engine state store is closed");
            }
        };
    }

    /** 目标替换可能已经发生，但尚未得到持久确认；不能解释为未保存。 */
    final class SaveUnconfirmedException extends RuntimeException {
        private final Path path;

        public SaveUnconfirmedException(Path path, Throwable cause) {
            super("deployment target save is unconfirmed: " + path, cause);
            this.path = Objects.requireNonNull(path, "path");
        }

        public Path path() { return path; }
    }
}
