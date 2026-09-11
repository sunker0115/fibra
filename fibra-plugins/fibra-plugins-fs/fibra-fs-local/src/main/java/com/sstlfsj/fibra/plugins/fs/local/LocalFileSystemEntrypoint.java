package com.sstlfsj.fibra.plugins.fs.local;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.plugins.fs.FileSystemServices;
import com.sstlfsj.fibra.plugins.tool.ToolServices;
import reactor.core.publisher.Mono;

/** Formal provider entrypoint; shared types remain supplied by its manifest dependency. */
public final class LocalFileSystemEntrypoint implements PluginEntrypoint<LocalFileSystemConfig> {
    @Override
    public PluginDefinition<LocalFileSystemConfig> definition() {
        return PluginDefinition.builder("fs-local", LocalFileSystemConfig.class,
                () -> (context, config) -> {
                    var fileSystem = new LocalFileSystem(config.rootPath());
                    context.services().provide(FileSystemServices.FILE_SYSTEM, fileSystem);
                    context.services().provide(ToolServices.RESULT_SPILL_STORE,
                        new LocalResultSpillStore(fileSystem, config.rootPath(), config.spillDirectory()));
                    return Mono.empty();
                })
            .provide(FileSystemServices.FILE_SYSTEM)
            .provide(ToolServices.RESULT_SPILL_STORE)
            .build();
    }
}
