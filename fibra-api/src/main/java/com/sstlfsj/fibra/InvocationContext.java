package com.sstlfsj.fibra;

import org.reactivestreams.Publisher;

import java.util.Objects;
import java.util.function.Supplier;
import com.sstlfsj.fibra.logging.FibraLogger;
import com.sstlfsj.fibra.logging.LoggerIntercept;

public final class InvocationContext {
    private final Context caller;
    private final String serviceName;

    InvocationContext(Context caller, String serviceName) {
        this.caller = Objects.requireNonNull(caller, "caller");
        this.serviceName = serviceName;
    }

    public Context caller() {
        return caller;
    }

    public FibraLogger logger() {
        var resolvedName = serviceName;
        for (var value : caller.interceptValues("logger")) {
            if (value instanceof LoggerIntercept intercept && intercept.name() != null) {
                resolvedName = intercept.name();
            }
        }
        return caller.logger(resolvedName);
    }

    public <T> BoundService<T> service(ServiceKey<T> key) {
        return caller.service(key);
    }

    public <R> Associated<R> associate(R receiver) {
        return caller.associate(receiver);
    }

    public EffectHandle effect(Supplier<? extends Disposable> source, String label) {
        return caller.effect(source, label);
    }

    public EffectHandle effect(Publisher<? extends Disposable> source, String label) {
        return caller.effect(source, label);
    }

    public <C> Fibra plugin(PluginDescriptor<C> descriptor, Plugin<C> plugin, C config) {
        return caller.plugin(descriptor, plugin, config);
    }
}
