package com.sstlfsj.fibra;

import org.reactivestreams.Publisher;

import java.util.function.Supplier;

public interface Effects {
    EffectHandle add(Disposable disposable);

    /** 登记所有权后立即执行 source；初始化异常同步传播给调用者。 */
    EffectHandle effect(Supplier<? extends Disposable> source);

    EffectHandle effect(Supplier<? extends Disposable> source, String label);

    /** 订阅资源流；通过返回句柄的 ready() 等待初始化完成或观察其异常。 */
    EffectHandle collect(Publisher<? extends Disposable> source);

    EffectHandle collect(Publisher<? extends Disposable> source, String label);

    EffectHandle supervise(Publisher<?> health, String label);
}
