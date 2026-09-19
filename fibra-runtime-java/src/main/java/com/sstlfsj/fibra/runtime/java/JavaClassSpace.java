package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.artifact.ArtifactId;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** 从已归属的 loader 物化定义；不拥有或关闭一组 loader。 */
final class JavaClassSpace {
    private JavaClassSpace() { }

    /** 静态检查不运行初始化器、入口构造器或 definition。 */
    static void validate(ArtifactId id, JavaFacetDescriptor descriptor, PluginClassLoader loader) {
        if (descriptor.entrypoint().isEmpty()) return;
        try {
            var type = Class.forName(descriptor.entrypoint().orElseThrow(), false, loader);
            if (!PluginEntrypoint.class.isAssignableFrom(type)
                || java.lang.reflect.Modifier.isAbstract(type.getModifiers())
                || !java.lang.reflect.Modifier.isPublic(type.getModifiers())
                || !java.lang.reflect.Modifier.isPublic(type.getDeclaredConstructor().getModifiers())) {
                throw new JavaRuntimeException(JavaRuntimePhase.LOAD, id,
                    "entrypoint must be a concrete public PluginEntrypoint with a public no-arg constructor", null);
            }
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new JavaRuntimeException(JavaRuntimePhase.LOAD, id, "cannot load Java facet entrypoint", failure);
        }
    }

    static JavaDefinitionEntry<?> entry(ArtifactId id, JavaFacetDescriptor descriptor,
                                       PluginClassLoader loader) {
        if (descriptor.entrypoint().isEmpty()) return null;
        return entry(id, descriptor.entrypoint().orElseThrow(), loader);
    }

    private static JavaDefinitionEntry<?> entry(ArtifactId id, String entrypointName,
                                               PluginClassLoader loader) {
        try {
            var type = Class.forName(entrypointName, true, loader);
            var entrypoint = type.getDeclaredConstructor().newInstance();
            if (!(entrypoint instanceof PluginEntrypoint<?> pluginEntrypoint)) {
                throw new JavaRuntimeException(JavaRuntimePhase.LOAD, id,
                    "entrypoint does not implement PluginEntrypoint", null);
            }
            return entry(pluginEntrypoint);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new JavaRuntimeException(JavaRuntimePhase.LOAD, id, "cannot load Java plugin entrypoint", failure);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static JavaDefinitionEntry<?> entry(PluginEntrypoint<?> entrypoint) {
        var definition = entrypoint.definition();
        var mapper = new YAMLMapper();
        return new JavaDefinitionEntry(definition, value -> {
            if (value == null || definition.configType().isInstance(value)) return value;
            return mapper.convertValue(value, definition.configType());
        });
    }
}
