package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.Scope;
import com.sstlfsj.fibra.internal.DefaultRuntimeDomain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.List;

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

    /** 指定 Scope 及其真实后代的资源清理失败；不改变普通关闭的错误隔离语义。 */
    public List<RuntimeDomainSnapshot.CleanupFailure> cleanupFailures(Scope scope) {
        return delegate.cleanupFailures(scope);
    }

    /** 最新诊断事实；变化在 lifecycle lane 上合并，慢订阅者可跳过中间快照。域关闭后完成。 */
    public Flux<RuntimeDomainSnapshot> snapshots() {
        return delegate.snapshots();
    }

    /** 等待本域已创建的插件实例完成当前生命周期收敛。 */
    public Mono<Void> settled() {
        return delegate.settled();
    }

    public Mono<Void> closeAsync() {
        return delegate.closeAsync();
    }

    @Override
    public void close() {
        closeAsync().block();
    }
}
