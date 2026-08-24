package com.sstlfsj.fibra.loader.pf4j;

import reactor.core.scheduler.Schedulers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

final class LoaderOperationGate {
    private final Object stateLock = new Object();

    private volatile IdentitySnapshot snapshot = IdentitySnapshot.empty();
    private volatile boolean closed;
    private Thread owner;
    private int depth;
    private boolean closing;

    <T> T runExclusive(Supplier<T> action, Supplier<IdentitySnapshot> snapshotSupplier) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
        rejectNonBlockingThread();
        var outermost = acquire();
        try {
            var result = action.get();
            if (outermost) {
                snapshot = Objects.requireNonNull(snapshotSupplier.get(), "snapshot");
            }
            return result;
        } finally {
            release();
        }
    }

    IdentitySnapshot snapshot() {
        if (closed) {
            throw new IllegalStateException("FibraPluginLoader is closed");
        }
        return snapshot;
    }

    void close(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (closed) {
            return;
        }
        rejectNonBlockingThread();
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            if (owner != null || closing) {
                throw busy("cannot close FibraPluginLoader during an active operation");
            }
            owner = Thread.currentThread();
            depth = 1;
            closing = true;
        }

        var succeeded = false;
        try {
            action.run();
            succeeded = true;
        } finally {
            synchronized (stateLock) {
                owner = null;
                depth = 0;
                closing = false;
                if (succeeded) {
                    snapshot = IdentitySnapshot.empty();
                    closed = true;
                }
            }
        }
    }

    boolean isClosed() {
        return closed;
    }

    boolean isOwnedByCurrentThread() {
        synchronized (stateLock) {
            return owner == Thread.currentThread() && depth > 0 && !closing;
        }
    }

    private boolean acquire() {
        var current = Thread.currentThread();
        synchronized (stateLock) {
            if (closed) {
                throw new IllegalStateException("FibraPluginLoader is closed");
            }
            if (closing) {
                throw busy("FibraPluginLoader is closing");
            }
            if (owner == null) {
                owner = current;
                depth = 1;
                return true;
            }
            if (owner == current) {
                depth++;
                return false;
            }
            throw busy("another FibraPluginLoader operation is active");
        }
    }

    private void release() {
        synchronized (stateLock) {
            if (owner != Thread.currentThread() || depth == 0) {
                throw new IllegalStateException("current thread does not own loader operation");
            }
            depth--;
            if (depth == 0) {
                owner = null;
            }
        }
    }

    private static void rejectNonBlockingThread() {
        if (Schedulers.isInNonBlockingThread()) {
            throw busy("synchronous loader management is forbidden on a non-blocking thread");
        }
    }

    private static FibraPluginLoaderBusyException busy(String message) {
        return new FibraPluginLoaderBusyException(message);
    }

    record IdentitySnapshot(Map<String, String> artifactVersions, List<String> entryIds) {
        IdentitySnapshot {
            artifactVersions = Collections.unmodifiableMap(new LinkedHashMap<>(artifactVersions));
            entryIds = List.copyOf(entryIds);
        }

        static IdentitySnapshot empty() {
            return new IdentitySnapshot(Map.of(), List.of());
        }

        List<String> artifactIds() {
            return List.copyOf(artifactVersions.keySet());
        }
    }
}
