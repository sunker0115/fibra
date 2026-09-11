package com.sstlfsj.fibra.plugins.fs;

import com.sstlfsj.fibra.InvocationContext;
import reactor.core.publisher.Mono;

public interface FileSystem {
    Mono<FsTarget> resolve(InvocationContext context, String path, String cwd);

    Mono<FsInfo> stat(InvocationContext context, FsTarget target);

    Mono<String> readText(InvocationContext context, FsTarget target, long maxBytes);

    Mono<FsWriteResult> writeText(InvocationContext context, FsTarget target, String content,
        FsWriteIntent intent);

    Mono<FsEditResult> editText(InvocationContext context, FsTarget target, FsEdit edit,
        FsEditIntent intent);
}
