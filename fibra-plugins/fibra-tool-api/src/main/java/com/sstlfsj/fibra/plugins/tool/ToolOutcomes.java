package com.sstlfsj.fibra.plugins.tool;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** 只归一化已知工具失败；准入、传输与未知错误继续传播。 */
public final class ToolOutcomes {
    private ToolOutcomes() {
    }

    public static Mono<ToolOutcome> normalize(Supplier<? extends Mono<ToolResult>> invocation) {
        Objects.requireNonNull(invocation, "invocation");
        return Mono.defer(invocation)
            .switchIfEmpty(Mono.error(new IllegalStateException("tool invocation returned no result")))
            .<ToolOutcome>map(ToolOutcome.Success::new)
            .onErrorResume(ToolException.class, exception -> Mono.just(new ToolOutcome.Failure(
                exception.failure(), List.of(ToolContent.text("Error: " + exception.failure().message())))));
    }
}
