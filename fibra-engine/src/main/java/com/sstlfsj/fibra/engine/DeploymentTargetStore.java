package com.sstlfsj.fibra.engine;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** 单写者完整持久目标。只有本存储确认的 load/save 才签发 durable token。 */
public interface DeploymentTargetStore extends AutoCloseable {
    Optional<StoredTarget> load();

    DurableTargetToken save(long expectedRevision, DeploymentTarget target);

    @Override default void close() { }

    static DeploymentTargetStore inMemory() {
        return new DeploymentTargetStore() {
            private StoredTarget current;
            private boolean closed;
            public synchronized Optional<StoredTarget> load() {
                ensureOpen();
                return Optional.ofNullable(current);
            }
            public synchronized DurableTargetToken save(long expectedRevision, DeploymentTarget target) {
                ensureOpen();
                checkRevision(expectedRevision, current == null ? 0 : current.target().targetRevision(), target);
                current = StoredTarget.confirmed(target);
                return current.token();
            }
            public synchronized void close() { closed = true; }
            private void ensureOpen() {
                if (closed) throw new IllegalStateException("deployment target store is closed");
            }
        };
    }

    static void checkRevision(long expected, long actual, DeploymentTarget target) {
        Objects.requireNonNull(target, "target");
        if (expected != actual) throw new IllegalStateException("deployment target revision conflict: expected "
            + expected + ", actual " + actual);
        if (target.targetRevision() != Math.incrementExact(expected)) {
            throw new IllegalArgumentException("target revision must increment exactly once");
        }
    }

    final class StoredTarget {
        private final DeploymentTarget target;
        private final DurableTargetToken token;
        private StoredTarget(DeploymentTarget target) {
            this.target = Objects.requireNonNull(target, "target");
            token = DurableTargetToken.issue(target.targetRevision(), target.targetDigest());
        }
        static StoredTarget confirmed(DeploymentTarget target) { return new StoredTarget(target); }
        public DeploymentTarget target() { return target; }
        public DurableTargetToken token() { return token; }
    }

    final class SaveUnconfirmedException extends RuntimeException {
        private final Path path;
        public SaveUnconfirmedException(Path path, Throwable cause) {
            super("deployment target save is unconfirmed: " + path, cause);
            this.path = Objects.requireNonNull(path, "path");
        }
        public Path path() { return path; }
    }
}
