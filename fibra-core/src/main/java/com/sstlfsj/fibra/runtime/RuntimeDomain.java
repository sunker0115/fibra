package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.Scope;
import com.sstlfsj.fibra.internal.DefaultRuntimeDomain;
import reactor.core.publisher.Mono;

import java.util.Objects;

/** 在一个 FibraRuntime lifecycle lane 内独立拥有可见性与资源树的托管运行域。 */
public final class RuntimeDomain implements AutoCloseable {
    private final DefaultRuntimeDomain delegate;

    RuntimeDomain(DefaultRuntimeDomain delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public String name() {
        return delegate.name();
    }

    public Scope rootScope() {
        return delegate.rootScope();
    }

    public boolean isClosed() {
        return delegate.isClosed();
    }

    public RuntimeDomainSnapshot snapshot() {
        return delegate.snapshot();
    }

    public Mono<Void> closeAsync() {
        return delegate.closeAsync();
    }

    @Override
    public void close() {
        closeAsync().block();
    }
}
