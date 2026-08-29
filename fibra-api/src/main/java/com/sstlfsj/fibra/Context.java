package com.sstlfsj.fibra;

import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.EventOptions;
import com.sstlfsj.fibra.event.EventTarget;
import com.sstlfsj.fibra.event.Next;
import com.sstlfsj.fibra.logging.FibraLogger;
import com.sstlfsj.fibra.logging.LoggerService;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Fibra 运行时的作用域、资源所有权和事件入口。 */
public interface Context extends AutoCloseable {
    Context root();

    Fibra fibra();

    Context extend();

    Context extend(Map<String, ?> values);

    Object metadata(String name);

    <T> Context isolate(ServiceKey<T> key);

    <T> Context isolate(ServiceKey<T> key, Object label);

    Context isolate(String serviceName);

    Context isolate(String serviceName, Object label);

    <T> Context intercept(ServiceKey<T> key, Object config);

    Context intercept(String name, Object config);

    Object intercept(ServiceKey<?> key);

    List<Object> interceptValues(String name);

    LoggerService logging();

    FibraLogger logger();

    FibraLogger logger(String name);

    <T> ServiceRegistration<T> provide(ServiceKey<T> key, T value);

    <T> T get(ServiceKey<T> key);

    <T> T get(ServiceKey<T> key, boolean strict);

    <T> void set(ServiceKey<T> key, T value);

    default <T> BoundService<T> service(ServiceKey<T> key) {
        return new BoundService<>(this, key);
    }

    <R, T> EffectHandle accessor(PropertyKey<R, T> key, PropertyAccessor<R, T> accessor);

    default <R> Associated<R> associate(R receiver) {
        return new Associated<>(this, receiver);
    }

    <R, T> T get(PropertyKey<R, T> key, R receiver);

    <R, T> void set(PropertyKey<R, T> key, R receiver, T value);

    EffectHandle effect(Supplier<? extends Disposable> source);

    EffectHandle effect(Supplier<? extends Disposable> source, String label);

    EffectHandle effectSync(SyncEffect source);

    EffectHandle effectSync(SyncEffect source, String label);

    EffectHandle effectMany(Iterable<? extends Disposable> source);

    EffectHandle effectMany(Iterable<? extends Disposable> source, String label);

    EffectHandle effect(Publisher<? extends Disposable> source);

    EffectHandle effect(Publisher<? extends Disposable> source, String label);

    default Fibra plugin(PluginDescriptor<Void> descriptor, Plugin<Void> plugin) {
        return plugin(descriptor, plugin, null);
    }

    <C> Fibra plugin(PluginDescriptor<C> descriptor, Plugin<C> plugin, C config);

    default Fibra plugin(String name, Plugin<Void> plugin) {
        return plugin(name, plugin, null);
    }

    <C> Fibra plugin(String name, Plugin<C> plugin, C config);

    <C, P> Fibra plugin(PluginDescriptor<C> descriptor, PluginFactory<C, P> factory,
                        PluginInitializer<P> initializer, C config);

    PluginRegistry registry();

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

    Mono<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().block();
    }
}
