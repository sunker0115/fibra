package com.sstlfsj.fibra.plugins.storage;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.value.LiteralValue;
import reactor.core.publisher.Mono;

/** Realm-scoped configuration store with serialized durable writes. */
public interface ConfigStore {
    ConfigDocument load(InvocationContext context);

    Mono<ConfigDocument> put(InvocationContext context, String key, LiteralValue value);

    Mono<ConfigDocument> remove(InvocationContext context, String key);

    /**
     * Registers an ordered, non-replaying listener. Implementations register the returned,
     * idempotent handle in {@code context.effects()} and also return it for early cancellation.
     */
    Disposable subscribe(InvocationContext context, ConfigChangeListener listener);
}
