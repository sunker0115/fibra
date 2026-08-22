package com.sstlfsj.fibra;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Supplier;

/** 单个插件实例的生命周期状态机。 */
public interface Fibra extends Disposable {
    Context context();

    Context parentContext();

    String name();

    Long uid();

    FibraState state();

    Object config();

    boolean isRoot();

    EffectHandle effect(Supplier<? extends Disposable> source, String label);

    EffectHandle effectSync(SyncEffect source, String label);

    EffectHandle effectMany(Iterable<? extends Disposable> source, String label);

    EffectHandle effect(Publisher<? extends Disposable> source, String label);

    List<EffectMetadata> effects();

    Mono<Fibra> await();

    Mono<Fibra> ready();

    Mono<Fibra> restart();

    <C> Mono<Fibra> update(C config);

    <C> Mono<Fibra> update(C config, boolean noSave);

    boolean requires(String serviceName);

    void require(ServiceKey<?> key);

    void require(ServiceKey<?> key, Object intercept);
}
