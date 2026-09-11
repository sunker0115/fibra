package com.sstlfsj.fibra.plugins.fs.local;

import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.plugins.fs.FsWriteIntent;
import com.sstlfsj.fibra.plugins.tool.ResultSpillStore;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** Optional, root-confined text spill implementation for tool consumers. */
final class LocalResultSpillStore implements ResultSpillStore {
    private final LocalFileSystem fileSystem;
    private final String relativeDirectory;

    LocalResultSpillStore(LocalFileSystem fileSystem, Path root, String relativeDirectory) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
        this.relativeDirectory = Objects.requireNonNull(relativeDirectory, "relativeDirectory");
        Objects.requireNonNull(root, "root");
    }

    @Override
    public Mono<String> store(InvocationContext context, String suggestedName, String content) {
        var name = fileName(suggestedName);
        return fileSystem.ensureDirectory(context, relativeDirectory)
            .then(fileSystem.resolve(context, name, relativeDirectory))
            .flatMap(target -> fileSystem.writeText(context, target, content,
                new FsWriteIntent.CreateIfAbsent()).thenReturn(target.displayPath()));
    }

    private static String fileName(String suggestedName) {
        var base = suggestedName == null ? "result" : suggestedName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isBlank() || base.equals(".") || base.equals("..")) base = "result";
        return base + '-' + UUID.randomUUID() + ".txt";
    }
}
