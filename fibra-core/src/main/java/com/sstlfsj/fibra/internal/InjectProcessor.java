package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.PluginDescriptor;
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
import java.util.Arrays;
import java.util.List;

final class InjectProcessor {
    private InjectProcessor() {
    }

    public static List<ServiceKey<?>> instanceDependencies(Class<?> type) {
        var result = new ArrayList<ServiceKey<?>>();
        for (var current : hierarchy(type)) {
            for (var annotation : current.getAnnotationsByType(InjectService.class)) {
                result.add(toKey(annotation, null));
            }
            for (var field : current.getDeclaredFields()) {
                var annotation = field.getAnnotation(InjectService.class);
                if (annotation != null) {
                    result.add(toKey(annotation, field.getType()));
                }
            }
        }
        return List.copyOf(result);
    }

    public static void prepare(Object instance, Context context) {
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
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                    throw new IllegalArgumentException("@InjectService field must be a mutable instance field: " + field);
                }
                var key = toKey(annotation, field.getType());
                try {
                    field.setAccessible(true);
                    field.set(instance, context.get(key));
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException("cannot inject field " + field, exception);
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
                if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) {
                    throw new IllegalArgumentException("@InjectService method must be a zero-argument instance method: " + method);
                }
                var builder = PluginDescriptor.<Void>builder(method.getDeclaringClass().getSimpleName()
                    + "." + method.getName());
                for (var annotation : annotations) {
                    builder.require(toKey(annotation, null));
                }
                context.plugin(builder.build(), (methodContext, ignored) -> invokeMethod(instance, method), null);
            }
        }
    }

    private static Publisher<? extends Disposable> invokeMethod(Object instance, Method method) {
        try {
            method.setAccessible(true);
            var result = method.invoke(instance);
            if (result == null) {
                return Flux.empty();
            }
            if (result instanceof Disposable disposable) {
                return Mono.just(disposable);
            }
            if (result instanceof Publisher<?> publisher) {
                return Flux.from(publisher).map(value -> {
                    if (value instanceof Disposable disposable) {
                        return disposable;
                    }
                    throw new IllegalArgumentException("injected method publisher emitted a non-disposable value");
                });
            }
            throw new IllegalArgumentException("injected method returned an unsupported value: " + result.getClass().getName());
        } catch (InvocationTargetException exception) {
            var cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("cannot invoke injected method " + method, exception);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ServiceKey<?> toKey(InjectService annotation, Class<?> inferredType) {
        var type = annotation.type() == Void.class ? inferredType : annotation.type();
        if (type == null) {
            throw new IllegalArgumentException("@InjectService on a type or method must declare type");
        }
        return ServiceKey.of(annotation.value(), (Class) type);
    }

    private static List<Class<?>> hierarchy(Class<?> type) {
        var result = new ArrayList<Class<?>>();
        for (var current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            result.add(0, current);
        }
        return result;
    }
}
