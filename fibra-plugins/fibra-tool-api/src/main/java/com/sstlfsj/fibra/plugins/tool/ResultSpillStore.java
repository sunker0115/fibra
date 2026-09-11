package com.sstlfsj.fibra.plugins.tool;

import com.sstlfsj.fibra.InvocationContext;
import reactor.core.publisher.Mono;

public interface ResultSpillStore {
    Mono<String> store(InvocationContext context, String suggestedName, String content);
}
