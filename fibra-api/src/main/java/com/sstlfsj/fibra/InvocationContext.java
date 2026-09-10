package com.sstlfsj.fibra;

import java.util.Objects;
import com.sstlfsj.fibra.logging.FibraLogger;

public final class InvocationContext {
    private final Context caller;
    private final String serviceName;

    InvocationContext(Context caller, String serviceName) {
        this.caller = Objects.requireNonNull(caller, "caller");
        this.serviceName = serviceName;
    }

    public static InvocationContext of(Context caller, String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be blank");
        }
        return new InvocationContext(caller, serviceName);
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

    public <T> ServiceRef<T> service(ServiceKey<T> key) {
        return caller.services().reference(key);
    }

    public Effects effects() {
        return caller.effects();
    }

    public Plugins plugins() {
        return caller.plugins();
    }
}
