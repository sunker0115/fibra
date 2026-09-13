package com.sstlfsj.fibra.cli.api;

import java.nio.file.Path;
import java.util.Objects;

public record CliProfile(String name, Path home, Path configRoot, Path pluginsRoot,
                         Path dataRoot) {
    public CliProfile {
        name = Objects.requireNonNull(name, "name");
        home = normalized(home, "home");
        configRoot = normalized(configRoot, "configRoot");
        pluginsRoot = normalized(pluginsRoot, "pluginsRoot");
        dataRoot = normalized(dataRoot, "dataRoot");
    }

    private static Path normalized(Path value, String name) {
        return Objects.requireNonNull(value, name).toAbsolutePath().normalize();
    }
}
