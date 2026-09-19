package com.sstlfsj.fibra;

import com.sstlfsj.fibra.logging.FibraLogger;

import java.util.Objects;

public final class InvocationContext {
    private final Context caller;
    private final String serviceName;
    private final CancellationToken cancellation;
    private final Context resourceContext;

    InvocationContext(Context caller, String serviceName, CancellationToken cancellation) {
        this(caller, serviceName, cancellation, caller);
    }

    InvocationContext(Context caller, String serviceName,
                      CancellationToken cancellation, Context resourceContext) {
        this.caller = Objects.requireNonNull(caller, "caller");
        this.serviceName = serviceName;
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.resourceContext = Objects.requireNonNull(resourceContext, "resourceContext");
        if (!caller.scope().sharesDomainWith(resourceContext.scope())) {
            throw new IllegalArgumentException(
                "caller and resource scope must belong to the same RuntimeDomain");
        }
    }

    public static InvocationContext of(Context caller, String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be blank");
        }
        return new InvocationContext(caller, serviceName, CancellationToken.never());
    }

    /**
     * 使用同一 RuntimeDomain 内的独立资源 Scope 创建调用上下文，同时保留 caller 的能力解析语境。
     */
    public static InvocationContext of(Context caller, ScopeView scope, String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be blank");
        }
        return new InvocationContext(caller, serviceName, CancellationToken.never(),
            scope.context());
    }

    public InvocationContext withCancellation(CancellationToken cancellation) {
        return new InvocationContext(caller, serviceName, cancellation, resourceContext);
    }

    /** 返回解析 realm、intercept、logger、plugins 和服务的能力 owner。 */
    public Context caller() {
        return caller;
    }

    public FibraLogger logger() {
        return caller.loggerForService(serviceName);
    }

    public String serviceName() {
        return serviceName;
    }

    public CancellationToken cancellation() {
        return cancellation;
    }

    /** 返回资源 owner 所在的生命周期 Scope；实际 owner 由 {@link #effects()} 的资源语境保留。 */
    public ScopeView scope() {
        return resourceContext.scope();
    }

    public <T> ServiceRef<T> service(ServiceKey<T> key) {
        return new ServiceRef<>(caller, key, cancellation, resourceContext);
    }

    public Effects effects() {
        return resourceContext.effects();
    }

    public Plugins plugins() {
        return caller.plugins();
    }
}
