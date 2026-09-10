package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.EffectHandle;
import com.sstlfsj.fibra.EffectMetadata;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Objects;

final class SupervisedResource implements OwnedEffect, Subscriber<Object> {
    private final ResourceOwner owner;
    private final LifecycleDispatcher lifecycle;
    private final String label;
    private final Sinks.One<EffectHandle> ready = Sinks.one();
    private final Sinks.One<Void> disposed = Sinks.one();

    private Subscription subscription;
    private boolean disposeRequested;
    private boolean terminated;

    SupervisedResource(ResourceOwner owner, Publisher<?> health, String label) {
        this.owner = Objects.requireNonNull(owner, "owner");
        lifecycle = owner.lifecycle();
        this.label = Objects.requireNonNull(label, "label");
        lifecycle.call(() -> {
            if (!owner.acceptsResources()) {
                throw new IllegalStateException(
                    "owner \"" + owner.ownerName() + "\" does not accept resources");
            }
            owner.addResource(this);
            return null;
        });
        try {
            Objects.requireNonNull(health, "health").subscribe(this);
        } catch (RuntimeException | Error failure) {
            lifecycle.call(() -> {
                fail(failure);
                return null;
            });
            throw failure;
        }
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        lifecycle.call(() -> {
            if (this.subscription != null) {
                subscription.cancel();
                return null;
            }
            this.subscription = Objects.requireNonNull(subscription, "subscription");
            if (disposeRequested) {
                subscription.cancel();
                finishDispose();
            } else {
                subscription.request(Long.MAX_VALUE);
                ready.tryEmitValue(this);
            }
            return null;
        });
    }

    @Override
    public void onNext(Object ignored) {
    }

    @Override
    public void onError(Throwable failure) {
        lifecycle.call(() -> {
            fail(Objects.requireNonNull(failure, "failure"));
            return null;
        });
    }

    @Override
    public void onComplete() {
        lifecycle.call(() -> {
            fail(new IllegalStateException(
                "supervised resource \"" + label + "\" terminated unexpectedly"));
            return null;
        });
    }

    @Override
    public Mono<Void> dispose() {
        lifecycle.call(() -> {
            if (!disposeRequested) {
                disposeRequested = true;
                if (subscription != null && !terminated) {
                    subscription.cancel();
                }
                finishDispose();
            }
            return null;
        });
        return disposed.asMono();
    }

    @Override
    public Mono<EffectHandle> ready() {
        return ready.asMono();
    }

    @Override
    public boolean isDisposed() {
        return lifecycle.call(() -> disposeRequested);
    }

    @Override
    public EffectMetadata metadata() {
        return new EffectMetadata(label, List.of());
    }

    private void fail(Throwable failure) {
        if (disposeRequested || terminated) {
            return;
        }
        terminated = true;
        owner.resourceFailed(failure);
    }

    private void finishDispose() {
        owner.removeResource(this);
        ready.tryEmitValue(this);
        disposed.tryEmitEmpty();
    }
}
