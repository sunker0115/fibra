package com.sstlfsj.fibra.registry;

import java.nio.file.Path;
import java.util.Objects;

/** package 身份只从严格读取的包内元数据取得；启用门必须由调用者显式指定。 */
public record PluginInstallRequest(Path source, boolean enabled) {
    public PluginInstallRequest {
        source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
    }
}
