package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

final class Cleanup {
    private Cleanup() {
    }

    static Mono<Void> allSettled(List<? extends Disposable> resources) {
        var failures = new ConcurrentLinkedQueue<Throwable>();
        return Flux.fromIterable(resources)
            .flatMap(resource -> Mono.defer(resource::dispose)
                .onErrorResume(error -> {
                    failures.add(error);
                    return Mono.empty();
                }))
            .then(Mono.defer(() -> {
                if (failures.isEmpty()) {
                    return Mono.empty();
                }
                var failure = new IllegalStateException("one or more resources failed to dispose");
                failures.forEach(failure::addSuppressed);
                return Mono.error(failure);
            }));
    }

    static <T> List<T> reversed(List<T> values) {
        var result = new ArrayList<T>(values.size());
        for (int index = values.size() - 1; index >= 0; index--) {
            result.add(values.get(index));
        }
        return result;
    }
}
