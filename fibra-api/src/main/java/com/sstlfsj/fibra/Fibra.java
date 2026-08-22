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

    /**
     * 等待当前 reload 或 unload 收敛，并传播启动错误。
     * 依赖缺失且状态稳定为 {@link FibraState#PENDING} 时也会正常完成，
     * 不表示实例已经 ACTIVE，也不等待未来 provider。
     */
    Mono<Fibra> await();

    /**
     * {@link #await()} 的语义别名，不额外断言实例已经 ACTIVE。
     */
    Mono<Fibra> ready();

    Mono<Fibra> restart();

    <C> Mono<Fibra> update(C config);

    <C> Mono<Fibra> update(C config, boolean noSave);

    boolean requires(String serviceName);

    void require(ServiceKey<?> key);

    void require(ServiceKey<?> key, Object intercept);
}
