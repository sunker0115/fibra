package com.sstlfsj.fibra.plugins.subprocess.local;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessServices;
import reactor.core.publisher.Mono;

import java.util.Objects;

public final class SubprocessLocalEntrypoint implements PluginEntrypoint<SubprocessLocalConfig> {
    @Override
    public PluginDefinition<SubprocessLocalConfig> definition() {
        return PluginDefinition.builder("fibra-subprocess-local", SubprocessLocalConfig.class,
            () -> (context, config) -> {
                context.services().provide(SubprocessServices.SUBPROCESS, new LocalSubprocess(config.nodeExecutable()));
                return Mono.empty();
            }).validator(config -> Objects.requireNonNull(config, "subprocess-local config"))
            .provide(SubprocessServices.SUBPROCESS).build();
    }
}
