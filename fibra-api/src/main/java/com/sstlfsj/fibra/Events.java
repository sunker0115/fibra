package com.sstlfsj.fibra;

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

public interface Events {
    <L> Disposable on(EventKey<L> key, L listener);

    <L> Disposable on(EventKey<L> key, L listener, EventOptions options);

    <L> Disposable once(EventKey<L> key, L listener);

    <L> Disposable once(EventKey<L> key, L listener, EventOptions options);

    <L> void emit(EventKey<L> key, Consumer<? super L> invocation);

    <L> void emit(EventTarget target, EventKey<L> key, Consumer<? super L> invocation);

    <L> Mono<Void> parallel(EventKey<L> key,
                            Function<? super L, ? extends Publisher<?>> invocation);

    <L> Mono<Void> parallel(EventTarget target, EventKey<L> key,
                            Function<? super L, ? extends Publisher<?>> invocation);

    <L, R> Mono<R> serial(EventKey<L> key,
                          Function<? super L, ? extends Publisher<R>> invocation);

    <L, R> Mono<R> serial(EventTarget target, EventKey<L> key,
                          Function<? super L, ? extends Publisher<R>> invocation);

    <L, R> R bail(EventKey<L> key, Function<? super L, ? extends R> invocation);

    <L, R> R bail(EventTarget target, EventKey<L> key,
                  Function<? super L, ? extends R> invocation);

    <L, R> R waterfall(EventKey<L> key,
                       BiFunction<? super L, Next<R>, ? extends R> invocation,
                       Supplier<? extends R> inner);

    <L, R> R waterfall(EventTarget target, EventKey<L> key,
                       BiFunction<? super L, Next<R>, ? extends R> invocation,
                       Supplier<? extends R> inner);
}
