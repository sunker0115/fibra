package com.sstlfsj.fibra.config;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public final class InMemoryDesiredStateRepository implements DesiredStateRepository {
    private final ReentrantLock lock = new ReentrantLock();
    private DesiredCompilation current;

    public InMemoryDesiredStateRepository(DesiredGraph initial) {
        current = compilation(Objects.requireNonNull(initial, "initial"));
    }

    public static InMemoryDesiredStateRepository empty() {
        return new InMemoryDesiredStateRepository(new DesiredGraph(List.of()));
    }

    @Override
    public DesiredCompilation load(PluginDefinitionResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        lock.lock();
        try {
            return current;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean writable() {
        return true;
    }

    @Override
    public DesiredStateWriteTransaction prepareReplace(String expectedRevision,
                                                       DesiredGraph candidate) {
        if (expectedRevision == null || expectedRevision.isBlank()) {
            throw new IllegalArgumentException("expectedRevision must not be blank");
        }
        Objects.requireNonNull(candidate, "candidate");
        lock.lock();
        try {
            if (!current.snapshot().revision().equals(expectedRevision)) {
                throw conflict(expectedRevision, current.snapshot().revision());
            }
            return new Transaction(expectedRevision, compilation(candidate));
        } finally {
            lock.unlock();
        }
    }

    private static DesiredCompilation compilation(DesiredGraph graph) {
        return new DesiredCompilation(new DesiredSourceSnapshot(
            "memory", UUID.randomUUID().toString(), java.util.Set.of()), graph, List.of());
    }

    private static ConfigException conflict(String expected, String actual) {
        return new ConfigException(new ConfigDiagnostic(ConfigStage.WRITE,
            "REVISION_CONFLICT", "expected revision " + expected + " but found " + actual,
            null, null), null);
    }

    private final class Transaction implements DesiredStateWriteTransaction {
        private final String expectedRevision;
        private final DesiredCompilation candidate;
        private State state = State.PREPARED;

        private Transaction(String expectedRevision, DesiredCompilation candidate) {
            this.expectedRevision = expectedRevision;
            this.candidate = candidate;
        }

        @Override
        public DesiredCompilation candidate() {
            return candidate;
        }

        @Override
        public DesiredCompilation commit() {
            lock.lock();
            try {
                if (state == State.COMMITTED) {
                    return current;
                }
                if (state == State.ROLLED_BACK) {
                    throw new IllegalStateException("desired state transaction is rolled back");
                }
                if (!current.snapshot().revision().equals(expectedRevision)) {
                    throw conflict(expectedRevision, current.snapshot().revision());
                }
                current = candidate;
                state = State.COMMITTED;
                return current;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void rollback() {
            if (state == State.PREPARED) {
                state = State.ROLLED_BACK;
            }
        }
    }

    private enum State {
        PREPARED,
        COMMITTED,
        ROLLED_BACK
    }
}
