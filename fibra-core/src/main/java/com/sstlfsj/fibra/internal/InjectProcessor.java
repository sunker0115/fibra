package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.annotation.InjectService;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

final class InjectProcessor {
    private InjectProcessor() {
    }

    static void prepare(Object instance, Context context, Class<?> injectionType) {
        if (injectionType == null) {
            return;
        }
        if (!injectionType.isInstance(instance)) {
            throw new IllegalArgumentException("plugin factory returned "
                + instance.getClass().getName() + " but injection type is "
                + injectionType.getName());
        }
        injectFields(instance, context);
        activateMethods(instance, context);
    }

    private static void injectFields(Object instance, Context context) {
        for (var current : hierarchy(instance.getClass())) {
            for (var field : current.getDeclaredFields()) {
                var annotation = field.getAnnotation(InjectService.class);
                if (annotation == null) {
                    continue;
                }
                if (Modifier.isStatic(field.getModifiers())
                    || Modifier.isFinal(field.getModifiers())) {
                    throw new IllegalArgumentException(
                        "@InjectService field must be mutable: " + field);
                }
                var key = key(annotation, field.getType());
                try {
                    field.setAccessible(true);
                    field.set(instance, context.services().require(key));
                } catch (IllegalAccessException failure) {
                    throw new IllegalStateException("cannot inject field " + field,
                        failure);
                }
            }
        }
    }

    private static void activateMethods(Object instance, Context context) {
        for (var current : hierarchy(instance.getClass())) {
            for (var method : current.getDeclaredMethods()) {
                var annotations = method.getAnnotationsByType(InjectService.class);
                if (annotations.length == 0) {
                    continue;
                }
                if (Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 0) {
                    throw new IllegalArgumentException(
                        "@InjectService method must be a zero-argument instance method: "
                            + method);
                }
                var builder = PluginDefinition.builder(
                    method.getDeclaringClass().getSimpleName() + '.' + method.getName(),
                    Void.class, () -> (childContext, ignored) ->
                        invoke(instance, method, childContext));
                for (var annotation : annotations) {
                    builder.require(key(annotation, null));
                }
                var parent = context.plugins().current().orElseThrow();
                context.plugins().mount(parent.id() + ":inject:" + method.getName(),
                    builder.build(), null);
            }
        }
    }

    private static Mono<Void> invoke(Object instance, Method method, Context context) {
        final Object result;
        try {
            method.setAccessible(true);
            result = method.invoke(instance);
        } catch (InvocationTargetException failure) {
            var cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("cannot invoke injected method " + method,
                failure);
        }
        if (result == null) {
            return Mono.empty();
        }
        if (result instanceof Disposable disposable) {
            context.effects().add(disposable);
            return Mono.empty();
        }
        if (result instanceof Publisher<?> publisher) {
            var disposables = Flux.from(publisher).map(value -> {
                if (value instanceof Disposable disposable) {
                    return disposable;
                }
                throw new IllegalArgumentException(
                    "injected method publisher emitted a non-disposable value");
            });
            return context.effects().collect(disposables).ready().then();
        }
        throw new IllegalArgumentException(
            "injected method returned unsupported type "
                + result.getClass().getName());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ServiceKey key(InjectService annotation, Class<?> inferredType) {
        var type = annotation.type() == Void.class
            ? inferredType : annotation.type();
        if (type == null) {
            throw new IllegalArgumentException(
                "@InjectService on a method must declare type");
        }
        return ServiceKey.of(annotation.value(), type);
    }

    private static List<Class<?>> hierarchy(Class<?> type) {
        var result = new ArrayList<Class<?>>();
        for (var current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            result.addFirst(current);
        }
        return result;
    }
}
