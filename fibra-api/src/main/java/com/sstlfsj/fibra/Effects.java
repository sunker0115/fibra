package com.sstlfsj.fibra;

import org.reactivestreams.Publisher;

import java.util.function.Supplier;

public interface Effects {
    EffectHandle add(Disposable disposable);

    EffectHandle effect(Supplier<? extends Disposable> source);

    EffectHandle effect(Supplier<? extends Disposable> source, String label);

    EffectHandle collect(Publisher<? extends Disposable> source);

    EffectHandle collect(Publisher<? extends Disposable> source, String label);

    EffectHandle supervise(Publisher<?> health, String label);
}
