package com.sstlfsj.fibra.config;

import java.nio.file.Path;
import java.util.Objects;

public final class FileDesiredStateRepository implements DesiredStateRepository {
    private final Path root;
    private final DesiredConfigCompiler compiler;

    public FileDesiredStateRepository(Path root, ConfigLimits limits) {
        this.root = Objects.requireNonNull(root, "root");
        compiler = new DesiredConfigCompiler(limits);
    }

    @Override
    public DesiredCompilation load(PluginDefinitionResolver resolver) {
        return compiler.compile(root, resolver);
    }
}
