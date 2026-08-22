package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Disposable;
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

final class EffectHandleImpl implements EffectHandle, Subscriber<Disposable> {
    private final DefaultFibra owner;
    private final LifecycleDispatcher lifecycle;
    private final String label;
    private final List<Disposable> collected = new ArrayList<>();
    private final List<EffectHandleImpl> children = new ArrayList<>();
    private final Sinks.One<EffectHandle> ready = Sinks.one();
    private final Sinks.One<Void> disposed = Sinks.one();

    private Subscription subscription;
    private boolean disposeRequested;
    private boolean explicitDispose;
    private boolean sourceSettled;
    private boolean teardownStarted;
    private Throwable sourceError;

    public EffectHandleImpl(DefaultFibra owner, Publisher<? extends Disposable> source, String label) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.lifecycle = owner.lifecycle();
        this.label = label;
        owner.addEffect(this);
        Objects.requireNonNull(source, "source").subscribe(this);
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        lifecycle.call(() -> {
            if (this.subscription != null) {
                subscription.cancel();
                return null;
            }
            this.subscription = subscription;
            subscription.request(1);
            return null;
        });
    }

    @Override
    public void onNext(Disposable disposable) {
        lifecycle.call(() -> {
            collect(Objects.requireNonNull(disposable, "effect source emitted null"));
            if (disposeRequested) {
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

    public boolean hasMetadata() {
        return label != null;
    }

    private void collect(Disposable disposable) {
        if (disposable instanceof EffectHandleImpl child && child.owner == owner) {
            owner.removeEffect(child);
            children.add(child);
        }
        collected.add(disposable);
    }

    private void settleSource(Throwable error) {
        if (sourceSettled) {
            return;
        }
        sourceSettled = true;
        sourceError = error;
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
        if (sourceSettled) {
            startTeardown();
        }
    }

    private void startTeardown() {
        if (teardownStarted) {
            return;
        }
        teardownStarted = true;
        var reverse = new ArrayList<Disposable>(collected.size());
        for (int index = collected.size() - 1; index >= 0; index--) {
            reverse.add(collected.get(index));
        }
        collected.clear();

        Flux.fromIterable(reverse)
            .concatMap(disposable -> Mono.defer(() ->
                Objects.requireNonNull(disposable.dispose(), "disposer returned null")), 1)
            .then()
            .publishOn(lifecycle.scheduler())
            .subscribe(
                ignored -> {
                },
                this::finishWithCleanupError,
                this::finishSuccessfully
            );
    }

    private void finishWithCleanupError(Throwable cleanupError) {
        owner.removeEffect(this);
        if (sourceError != null) {
            ready.tryEmitError(sourceError);
        }
        disposed.tryEmitError(cleanupError);
    }

    private void finishSuccessfully() {
        owner.removeEffect(this);
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
            .map(EffectHandleImpl::snapshotMetadata)
            .toList());
    }
}
