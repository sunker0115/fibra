package com.sstlfsj.fibra;

import java.util.Objects;
import com.sstlfsj.fibra.logging.FibraLogger;

public final class InvocationContext {
    private final Context caller;
    private final String serviceName;
    private final CancellationToken cancellation;

    InvocationContext(Context caller, String serviceName, CancellationToken cancellation) {
        this.caller = Objects.requireNonNull(caller, "caller");
        this.serviceName = serviceName;
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    public static InvocationContext of(Context caller, String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be blank");
        }
        return new InvocationContext(caller, serviceName, CancellationToken.never());
    }

    public InvocationContext withCancellation(CancellationToken cancellation) {
        return new InvocationContext(caller, serviceName, cancellation);
    }

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

    public <T> ServiceRef<T> service(ServiceKey<T> key) {
        return new ServiceRef<>(caller, key, cancellation);
    }

    public Effects effects() {
        return caller.effects();
    }

    public Plugins plugins() {
        return caller.plugins();
    }
}
