package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.CancellationSource;
import com.sstlfsj.fibra.CancellationToken;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;

/** 统一跟踪 CLI 调用的协作取消、准入关闭与完成屏障。 */
final class CliInvocationCoordinator {
    private final LinkedHashSet<Invocation> active = new LinkedHashSet<>();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private boolean stopping;
    private Invocation current;

    synchronized Invocation begin() {
        if (stopping) throw new IllegalStateException("CLI invocation admission is closed");
        var invocation = new Invocation(this);
        active.add(invocation);
        current = invocation;
        return invocation;
    }

    boolean cancelCurrent() {
        final Invocation invocation;
        synchronized (this) {
            invocation = current;
        }
        return invocation != null && invocation.cancel();
    }

    synchronized boolean currentCancelled() {
        return current != null && current.token().isCancelled();
    }

    synchronized CompletableFuture<Void> stopAdmission() {
        if (stopping) return drained;
        stopping = true;
        if (active.isEmpty()) drained.complete(null);
        return drained;
    }

    CompletableFuture<Void> stopAndCancel() {
        final java.util.List<Invocation> invocations;
        synchronized (this) {
            stopAdmission();
            invocations = new ArrayList<>(active);
        }
        invocations.forEach(Invocation::cancel);
        return drained;
    }

    private synchronized void finish(Invocation invocation) {
        if (!active.remove(invocation)) return;
        if (current == invocation) {
            current = active.stream().reduce((ignored, next) -> next).orElse(null);
        }
        if (stopping && active.isEmpty()) drained.complete(null);
    }

    static final class Invocation implements AutoCloseable {
        private final CliInvocationCoordinator owner;
        private final CancellationSource cancellation = new CancellationSource();
        private boolean closed;

        private Invocation(CliInvocationCoordinator owner) {
            this.owner = owner;
        }

        CancellationToken token() {
            return cancellation.token();
        }

        boolean cancel() {
            return cancellation.cancel();
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            owner.finish(this);
        }
    }
}
