package com.sstlfsj.fibra.runtime.java;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;

/** A frozen inventory of exact Host-defined public types for one API release. */
public record JavaApiContract(String id, String version, List<Class<?>> classes) {
    public JavaApiContract {
        if (id == null || id.isBlank() || version == null || version.isBlank()) {
            throw new IllegalArgumentException("Java API id and version must not be blank");
        }
        if (classes == null || classes.isEmpty()) {
            throw new IllegalArgumentException("Java API classes must not be empty");
        }
        var names = new HashSet<String>();
        for (var type : classes) {
            if (type == null || type.isPrimitive() || type.isArray() || type.isHidden()
                || type.isAnonymousClass() || type.isLocalClass()
                || !Modifier.isPublic(type.getModifiers())
                || type.getClassLoader() instanceof PluginClassLoader) {
                throw new IllegalArgumentException("ineligible Java API type: " + type);
            }
            if (!names.add(type.getName())) {
                throw new IllegalArgumentException("duplicate Java API type: " + type.getName());
            }
        }
        classes = List.copyOf(classes);
    }
}
