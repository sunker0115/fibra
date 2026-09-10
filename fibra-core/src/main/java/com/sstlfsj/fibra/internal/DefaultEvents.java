package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.Events;
import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.EventOptions;
import com.sstlfsj.fibra.event.EventTarget;
import com.sstlfsj.fibra.event.Next;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class DefaultEvents implements Events {
    private final DefaultContext context;

    DefaultEvents(DefaultContext context) {
        this.context = context;
    }

    @Override
    public <L> Disposable on(EventKey<L> key, L listener) {
        return on(key, listener, EventOptions.defaults());
    }

    @Override
    public <L> Disposable on(EventKey<L> key, L listener, EventOptions options) {
        return context.domain().events().on(context, key, listener, options, false);
    }

    @Override
    public <L> Disposable once(EventKey<L> key, L listener) {
        return once(key, listener, EventOptions.defaults());
    }

    @Override
    public <L> Disposable once(EventKey<L> key, L listener, EventOptions options) {
        return context.domain().events().on(context, key, listener, options, true);
    }

    @Override
    public <L> void emit(EventKey<L> key, Consumer<? super L> invocation) {
        emit(null, key, invocation);
    }

    @Override
    public <L> void emit(EventTarget target, EventKey<L> key, Consumer<? super L> invocation) {
        context.domain().events().emit(target, key, invocation);
    }

    @Override
    public <L> Mono<Void> parallel(EventKey<L> key,
                                   Function<? super L, ? extends Publisher<?>> invocation) {
        return parallel(null, key, invocation);
    }

    @Override
    public <L> Mono<Void> parallel(EventTarget target, EventKey<L> key,
                                   Function<? super L, ? extends Publisher<?>> invocation) {
        return context.domain().events().parallel(target, key, invocation);
    }

    @Override
    public <L, R> Mono<R> serial(EventKey<L> key,
                                 Function<? super L, ? extends Publisher<R>> invocation) {
        return serial(null, key, invocation);
    }

    @Override
    public <L, R> Mono<R> serial(EventTarget target, EventKey<L> key,
                                 Function<? super L, ? extends Publisher<R>> invocation) {
        return context.domain().events().serial(target, key, invocation);
    }

    @Override
    public <L, R> R bail(EventKey<L> key, Function<? super L, ? extends R> invocation) {
        return bail(null, key, invocation);
    }

    @Override
    public <L, R> R bail(EventTarget target, EventKey<L> key,
                         Function<? super L, ? extends R> invocation) {
        return context.domain().events().bail(target, key, invocation);
    }

    @Override
    public <L, R> R waterfall(EventKey<L> key,
                              BiFunction<? super L, Next<R>, ? extends R> invocation,
                              Supplier<? extends R> inner) {
        return waterfall(null, key, invocation, inner);
    }

    @Override
    public <L, R> R waterfall(EventTarget target, EventKey<L> key,
                              BiFunction<? super L, Next<R>, ? extends R> invocation,
                              Supplier<? extends R> inner) {
        return context.domain().events().waterfall(target, key, invocation, inner);
    }
}
