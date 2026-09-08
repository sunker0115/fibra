package com.sstlfsj.fibra.bridge;

import com.sstlfsj.fibra.InvocationContext;
import reactor.core.publisher.Mono;

@FunctionalInterface
public interface ContributionHandler<I, O> {
    Mono<O> invoke(InvocationContext context, I input);
}
