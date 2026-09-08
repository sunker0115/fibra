package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.Scope;
import com.sstlfsj.fibra.internal.DefaultFibraRuntime;
import reactor.core.publisher.Mono;

/** Fibra 内核的根所有者；关闭后不可恢复。 */
public final class FibraRuntime implements AutoCloseable {
    private final DefaultFibraRuntime delegate;

    private FibraRuntime(DefaultFibraRuntime delegate) {
        this.delegate = delegate;
    }

    public static FibraRuntime create() {
        return new FibraRuntime(new DefaultFibraRuntime());
    }

    public Scope rootScope() {
        return delegate.rootScope();
    }

    public boolean isClosed() {
        return delegate.isClosed();
    }

    public Mono<Void> closeAsync() {
        return delegate.closeAsync();
    }

    @Override
    public void close() {
        closeAsync().block();
    }
}
