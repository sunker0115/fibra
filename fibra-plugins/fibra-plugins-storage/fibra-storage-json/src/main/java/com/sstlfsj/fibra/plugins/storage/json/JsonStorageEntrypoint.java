package com.sstlfsj.fibra.plugins.storage.json;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.plugins.storage.StorageServices;
import reactor.core.publisher.Mono;

import java.nio.file.Path;

public final class JsonStorageEntrypoint implements PluginEntrypoint<JsonStorageConfig> {
    @Override
    public PluginDefinition<JsonStorageConfig> definition() {
        return PluginDefinition.builder("storage-json", JsonStorageConfig.class,
                () -> (context, config) -> {
                    var store = new JsonConfigStore(Path.of(config.root()).resolve("config.json"),
                        context.logger("fibra.storage.json"));
                    context.effects().add(store);
                    context.services().provide(StorageServices.CONFIG_STORE, store);
                    return Mono.empty();
                })
            .provide(StorageServices.CONFIG_STORE)
            .build();
    }
}
