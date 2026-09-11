package com.sstlfsj.fibra.plugins.shell.local;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.plugins.shell.ShellServices;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessServices;
import reactor.core.publisher.Mono;

import java.util.Objects;

public final class ShellLocalEntrypoint implements PluginEntrypoint<ShellLocalConfig> {
    @Override
    public PluginDefinition<ShellLocalConfig> definition() {
        return PluginDefinition.builder("fibra-shell-local", ShellLocalConfig.class,
            () -> (context, config) -> {
                context.services().provide(ShellServices.SHELL, new LocalShell(config));
                return Mono.empty();
            }).validator(config -> Objects.requireNonNull(config, "shell-local config"))
            .require(SubprocessServices.SUBPROCESS).provide(ShellServices.SHELL).build();
    }
}
