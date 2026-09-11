package com.sstlfsj.fibra.plugins.shell;

import com.sstlfsj.fibra.InvocationContext;
import reactor.core.publisher.Mono;

@FunctionalInterface
public interface Shell {
    Mono<ShellResult> run(InvocationContext invocation, ShellRequest request);
}
