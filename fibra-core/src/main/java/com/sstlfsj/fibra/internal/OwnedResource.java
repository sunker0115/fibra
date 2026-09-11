package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.DrainingDisposable;
import com.sstlfsj.fibra.EffectHandle;
import com.sstlfsj.fibra.EffectMetadata;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

final class OwnedResource implements OwnedEffect, DrainingDisposable, Subscriber<Disposable> {
    private final ResourceOwner owner;
    private final LifecycleDispatcher lifecycle;
    private final String label;
    private final List<Disposable> collected = new ArrayList<>();
    private final List<OwnedResource> children = new ArrayList<>();
    private final Sinks.One<EffectHandle> ready = Sinks.one();
    private final Sinks.One<Void> disposed = Sinks.one();
    private final Sinks.One<Void> sourceComplete = Sinks.one();
    private ResourceDrain drain;

    private Subscription subscription;
    private boolean disposeRequested;
    private boolean explicitDispose;
    private boolean sourceSettled;
    private boolean teardownStarted;
    private boolean drainedForTeardown;
    private Throwable sourceError;

    private OwnedResource(ResourceOwner owner, String label) {
        this.owner = Objects.requireNonNull(owner, "owner");
        lifecycle = owner.lifecycle();
        this.label = label;
        lifecycle.call(() -> {
            if (!owner.acceptsResources()) {
                throw new com.sstlfsj.fibra.FibraException(
                    owner instanceof DefaultScope
                        ? com.sstlfsj.fibra.FibraException.SCOPE_CLOSED
                        : com.sstlfsj.fibra.FibraException.EFFECT_INACTIVE,
                    "owner \"" + owner.ownerName() + "\" does not accept resources");
            }
            owner.addResource(this);
            return null;
        });
    }

    OwnedResource(ResourceOwner owner, Supplier<? extends Disposable> source, String label) {
        this(owner, label);
        lifecycle.call(() -> {
            try {
                collect(Objects.requireNonNull(source.get(), "effect source returned null"));
                settleSource(null);
            } catch (RuntimeException | Error failure) {
                settleSource(failure);
                throw failure;
            }
            return null;
        });
    }

    OwnedResource(ResourceOwner owner, Publisher<? extends Disposable> source, String label) {
        this(owner, label);
        try {
            Objects.requireNonNull(source, "source").subscribe(this);
        } catch (RuntimeException | Error failure) {
            lifecycle.call(() -> {
                settleSource(failure);
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
            subscription.request(1);
            return null;
        });
    }

    @Override
    public void onNext(Disposable disposable) {
        lifecycle.call(() -> {
            collect(Objects.requireNonNull(disposable, "effect source emitted null"));
            if (disposeRequested || drain != null) {
                subscription.cancel();
                settleSource(null);
            } else {
                subscription.request(1);
            }
            return null;
        });
    }

    @Override
    public void onError(Throwable error) {
        lifecycle.call(() -> {
            settleSource(Objects.requireNonNull(error, "error"));
            return null;
        });
    }

    @Override
    public void onComplete() {
        lifecycle.call(() -> {
            settleSource(null);
            return null;
        });
    }

    @Override
    public Mono<Void> dispose() {
        lifecycle.call(() -> {
            explicitDispose = true;
            requestDispose();
            return null;
        });
        return disposed.asMono();
    }

    @Override
    public Mono<Void> drain() {
        return lifecycle.call(() -> {
            if (drain == null) {
                var barrier = new ResourceDrain(lifecycle);
                freeze(barrier);
                barrier.start();
            }
            return drain.completion();
        });
    }

    void freeze(ResourceDrain barrier) {
        if (drain != null) {
            if (drain != barrier) {
                barrier.await(drain.completion());
            }
            return;
        }
        drain = barrier;
        barrier.await(sourceComplete.asMono());
        List.copyOf(collected).forEach(this::drainCollected);
    }

    private void drainCollected(Disposable resource) {
        if (resource instanceof OwnedResource || resource instanceof PluginInstanceImpl<?>) {
            drain.include(resource);
        } else if (resource instanceof DrainingDisposable draining) {
            drain.await(Mono.defer(draining::drain).doOnError(failure -> lifecycle.call(() -> {
                owner.domain().cleanupFailed(this, owner, label, failure);
                return null;
            })));
        }
    }

    @Override
    public ResourceOwner owner() {
        return owner;
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
        return lifecycle.call(this::snapshotMetadata);
    }

    private void collect(Disposable disposable) {
        if (disposable instanceof OwnedResource child && child.owner == owner) {
            owner.removeResource(child);
            children.add(child);
        }
        collected.add(disposable);
        if (drain != null) {
            drainCollected(disposable);
        }
    }

    private void settleSource(Throwable error) {
        if (sourceSettled) {
            return;
        }
        sourceSettled = true;
        sourceError = error;
        sourceComplete.tryEmitEmpty();
        if (error == null && !disposeRequested) {
            ready.tryEmitValue(this);
            return;
        }
        disposeRequested = true;
        startTeardown();
    }

    private void requestDispose() {
        if (disposeRequested) {
            return;
        }
        disposeRequested = true;
        drain();
        if (sourceSettled) {
            startTeardown();
        }
    }

    private void startTeardown() {
        if (teardownStarted) {
            return;
        }
        teardownStarted = true;
        var reverse = Cleanup.reversed(collected);
        drain().doOnSuccess(ignored -> drainedForTeardown = true)
            .thenMany(Flux.fromIterable(reverse)
            .concatMap(disposable -> Mono.defer(() ->
                Objects.requireNonNull(disposable.dispose(), "disposer returned null"))
                .doOnSuccess(ignored -> lifecycle.call(() -> {
                    removeCollected(disposable);
                    return null;
                })), 1))
            .then()
            .publishOn(lifecycle.scheduler())
            .subscribe(ignored -> { }, this::finishWithCleanupError, this::finishSuccessfully);
    }

    private void finishWithCleanupError(Throwable cleanupError) {
        if (!(cleanupError instanceof ResourceDrain.Failure) || drainedForTeardown) {
            owner.domain().cleanupFailed(this, owner, label, cleanupError);
        }
        if (sourceError != null) {
            if (cleanupError != sourceError) {
                cleanupError.addSuppressed(sourceError);
            }
            ready.tryEmitError(sourceError);
        } else {
            ready.tryEmitError(cleanupError);
        }
        disposed.tryEmitError(cleanupError);
    }

    private void removeCollected(Disposable disposable) {
        for (var index = 0; index < collected.size(); index++) {
            if (collected.get(index) == disposable) {
                collected.remove(index);
                return;
            }
        }
    }

    private void finishSuccessfully() {
        owner.removeResource(this);
        if (sourceError == null) {
            ready.tryEmitValue(this);
            disposed.tryEmitEmpty();
            return;
        }
        ready.tryEmitError(sourceError);
        if (explicitDispose) {
            disposed.tryEmitError(sourceError);
        } else {
            disposed.tryEmitEmpty();
        }
    }

    private EffectMetadata snapshotMetadata() {
        return new EffectMetadata(label, children.stream()
            .map(OwnedResource::snapshotMetadata)
            .toList());
    }
}
