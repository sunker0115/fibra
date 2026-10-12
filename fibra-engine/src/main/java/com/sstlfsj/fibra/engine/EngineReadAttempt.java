package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.PluginConfigurationResource;
import com.sstlfsj.fibra.engine.runtime.RuntimeDefinitionQuery;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Engine 唯一读取屏障的子句柄集合；实际 read/close 终态仍由底层句柄拥有。 */
final class EngineReadAttempt {
    private final EngineCommandLoop loop;
    private final List<Object> handles = new ArrayList<>();
    private boolean cleanupFailed;
    final Mono<Void> closed;

    EngineReadAttempt(EngineCommandLoop loop, Supplier<? extends RuntimeException> cleanupFailure) {
        this.loop = loop;
        closed = Mono.defer(() -> close(handles.size() - 1))
            .then(loop.<Void>call(() -> cleanupFailed ? Mono.error(cleanupFailure.get()) : Mono.empty()))
            .cache();
    }

    RuntimeDefinitionQuery register(RuntimeDefinitionQuery query) {
        handles.add(Objects.requireNonNull(query, "definition query"));
        return query;
    }

    PluginConfigurationResource register(PluginConfigurationResource resource) {
        handles.add(Objects.requireNonNull(resource, "configuration resource"));
        return resource;
    }

    private Mono<Void> close(int index) {
        if (index < 0) return Mono.empty();
        final Mono<Void> closing;
        try {
            var handle = handles.get(index);
            closing = handle instanceof RuntimeDefinitionQuery query
                ? Objects.requireNonNull(query.closeAsync(), "definition close")
                : Mono.fromRunnable(((PluginConfigurationResource) handle)::close);
        } catch (RuntimeException | Error foreign) {
            rethrowFatal(foreign);
            cleanupFailed = true;
            return loop.call(() -> close(index - 1));
        }
        return closing.then(loop.call(() -> {
                handles.remove(index);
                return Mono.<Void>empty();
            })).onErrorResume(foreign -> {
                rethrowFatal(foreign);
                return loop.call(() -> { cleanupFailed = true; return Mono.empty(); });
            }).then(loop.call(() -> close(index - 1)));
    }

    @SuppressWarnings("removal")
    static void rethrowFatal(Throwable error) {
        if (error instanceof VirtualMachineError fatal) throw fatal;
        if (error instanceof ThreadDeath fatal) throw fatal;
    }
}
