package com.sstlfsj.fibra.plugins.subprocess;

import com.sstlfsj.fibra.InvocationContext;
import reactor.core.publisher.Mono;

@FunctionalInterface
public interface Subprocess {
    Mono<ProcessUnit> spawn(InvocationContext invocation, SubprocessSpec spec);
}
