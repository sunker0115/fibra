package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.engine.PluginCatalogEntry;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** 从已归属的 loader 物化定义；不拥有或关闭一组 loader。 */
final class JavaClassSpace {
    private JavaClassSpace() { }

    static PluginCatalogEntry<?> entry(ArtifactId id, JavaPluginManifest manifest, PluginClassLoader loader) {
        if (manifest.entrypoint().isEmpty()) return null;
        try {
            var type = Class.forName(manifest.entrypoint().orElseThrow(), true, loader);
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
    private static PluginCatalogEntry<?> entry(PluginEntrypoint<?> entrypoint) {
        var definition = entrypoint.definition();
        var mapper = new YAMLMapper();
        return new PluginCatalogEntry(definition, value -> {
            if (value == null || definition.configType().isInstance(value)) return value;
            return mapper.convertValue(value, definition.configType());
        });
    }
}
