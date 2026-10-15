package com.sstlfsj.fibra.runtime.java;

import java.lang.reflect.Executable;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Checks only public/protected ABI; never initializes types or reads static values. */
final class JavaApiSignatures {
    private final Map<String, Class<?>> classes;
    private final Map<String, String> owners;
    private final Set<Type> visited = new HashSet<>();

    private JavaApiSignatures(Map<String, Class<?>> classes, Map<String, String> owners) {
        this.classes = classes;
        this.owners = owners;
    }

    static void validate(Map<String, Class<?>> classes, Map<String, String> owners) {
        var validator = new JavaApiSignatures(classes, owners);
        for (var type : classes.values()) {
            try {
                validator.signature(type);
            } catch (LinkageError | TypeNotPresentException
                     | java.lang.reflect.MalformedParameterizedTypeException | SecurityException failure) {
                throw new IllegalArgumentException("cannot resolve Java API signature: " + type.getName(), failure);
            }
        }
    }

    private void signature(Class<?> type) {
        reference(type.getEnclosingClass());
        reference(type.getGenericSuperclass());
        references(type.getGenericInterfaces());
        references(type.getTypeParameters());
        for (var field : type.getDeclaredFields()) {
            if (exposed(field.getModifiers())) reference(field.getGenericType());
        }
        for (var method : type.getDeclaredMethods()) {
            if (exposed(method.getModifiers())) {
                reference(method.getGenericReturnType());
                executable(method);
            }
        }
        for (var constructor : type.getDeclaredConstructors()) {
            if (exposed(constructor.getModifiers())) executable(constructor);
        }
        for (var nested : type.getDeclaredClasses()) {
            if (exposed(nested.getModifiers())) reference(nested);
        }
        if (type.isSealed()) {
            for (var permitted : type.getPermittedSubclasses()) {
                if (Modifier.isPublic(permitted.getModifiers())) reference(permitted);
            }
        }
    }

    private static boolean exposed(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private void executable(Executable executable) {
        references(executable.getGenericParameterTypes());
        references(executable.getGenericExceptionTypes());
        references(executable.getTypeParameters());
    }

    private void references(Type[] types) {
        for (var type : types) reference(type);
    }

    private void reference(Type type) {
        if (type == null || !visited.add(type)) return;
        if (type instanceof Class<?> value) {
            if (value.isArray()) {
                reference(value.getComponentType());
            } else if (!value.isPrimitive()) {
                if (!Modifier.isPublic(value.getModifiers())) {
                    throw new IllegalArgumentException("nonpublic Java API signature type: " + value.getName());
                }
                reference(value.getEnclosingClass());
                var exported = classes.get(value.getName());
                if (exported == value) return;
                if (exported != null || JavaApiContracts.baseline(value.getName()) != value) {
                    throw new IllegalArgumentException("Java API signature requires exact type " + value.getName()
                        + " owned by " + owners.getOrDefault(value.getName(), "an unexported API")
                        + (exported != null ? " (different Class identity)" : " (missing explicit import)"));
                }
            }
        } else if (type instanceof ParameterizedType value) {
            reference(value.getRawType());
            reference(value.getOwnerType());
            references(value.getActualTypeArguments());
        } else if (type instanceof TypeVariable<?> value) {
            references(value.getBounds());
        } else if (type instanceof WildcardType value) {
            references(value.getLowerBounds());
            references(value.getUpperBounds());
        } else if (type instanceof GenericArrayType value) {
            reference(value.getGenericComponentType());
        } else {
            throw new IllegalArgumentException("unsupported Java API signature type: " + type);
        }
    }
}
