package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.EffectHandle;
import com.sstlfsj.fibra.Effects;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Supplier;

final class DefaultEffects implements Effects {
    private final ResourceOwner owner;

    DefaultEffects(ResourceOwner owner) {
        this.owner = owner;
    }

    @Override
    public EffectHandle add(Disposable disposable) {
        return collect(Mono.just(Objects.requireNonNull(disposable, "disposable")));
    }

    @Override
    public EffectHandle effect(Supplier<? extends Disposable> source) {
        return effect(source, "anonymous");
    }

    @Override
    public EffectHandle effect(Supplier<? extends Disposable> source, String label) {
        Objects.requireNonNull(source, "source");
        return new OwnedResource(owner, source, label);
    }

    @Override
    public EffectHandle collect(Publisher<? extends Disposable> source) {
        return collect(source, "anonymous");
    }

    @Override
    public EffectHandle collect(Publisher<? extends Disposable> source, String label) {
        return new OwnedResource(owner, Objects.requireNonNull(source, "source"), label);
    }

    @Override
    public EffectHandle supervise(Publisher<?> health, String label) {
        if (owner.pluginInstance() == null) {
            throw new IllegalStateException("supervision requires a plugin-owned context");
        }
        return new SupervisedResource(owner,
            Objects.requireNonNull(health, "health"), label);
    }
}
