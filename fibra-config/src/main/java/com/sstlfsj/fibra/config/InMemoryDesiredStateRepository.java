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
            return new Transaction(current, compilation(candidate));
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
        private final DesiredCompilation previous;
        private final DesiredCompilation candidate;
        private State state = State.PREPARED;

        private Transaction(DesiredCompilation previous, DesiredCompilation candidate) {
            this.previous = previous;
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
                if (!current.snapshot().revision().equals(previous.snapshot().revision())) {
                    throw conflict(previous.snapshot().revision(),
                        current.snapshot().revision());
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
            lock.lock();
            try {
                if (state == State.ROLLED_BACK) {
                    return;
                }
                if (state == State.COMMITTED) {
                    if (!current.snapshot().revision().equals(
                        candidate.snapshot().revision())) {
                        throw conflict(candidate.snapshot().revision(),
                            current.snapshot().revision());
                    }
                    current = previous;
                }
                state = State.ROLLED_BACK;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void close() {
            lock.lock();
            try {
                if (state == State.PREPARED) {
                    state = State.ROLLED_BACK;
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private enum State {
        PREPARED,
        COMMITTED,
        ROLLED_BACK
    }
}
