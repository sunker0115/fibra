package com.sstlfsj.fibra.bridge;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.DrainingDisposable;
import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.InvocationContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** 单个 RuntimeDomain 拥有的 contribution 注册表与调用排空边界。 */
public final class ContributionDirectory implements ContributionRegistrar, AutoCloseable {
    private final Object monitor = new Object();
    private final Map<ContributionId, Entry<?, ?, ?>> entries = new LinkedHashMap<>();
    private final Set<Entry<?, ?, ?>> liveEntries = new LinkedHashSet<>();
    private final Sinks.Many<ContributionDirectoryView> views =
        Sinks.many().replay().latest();
    private long revision;
    private boolean closed;
    private Mono<Void> closing;

    public ContributionDirectory() {
        views.tryEmitNext(viewUnsafe());
    }

    @Override
    public <D, I, O> Mono<ContributionRegistration> register(
        Context owner, ContributionKind<D, I, O> kind,
        String providerInstanceId, String localName, D descriptor,
        ContributionHandler<I, O> handler) {
        var binding = new ContributionBinding<>(kind, localName, descriptor, handler);
        return registerAll(owner, providerInstanceId, List.of(binding),
            Disposables.noop()).map(List::getFirst);
    }

    @Override
    public Mono<List<ContributionRegistration>> registerAll(
        Context owner, String providerInstanceId,
        List<ContributionBinding<?, ?, ?>> bindings, Disposable afterDrain) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(afterDrain, "afterDrain");
        var copy = List.copyOf(bindings);
        if (copy.isEmpty()) {
            return Mono.error(new IllegalArgumentException("bindings must not be empty"));
        }
        return Mono.defer(() -> {
            var registrations = new AtomicReference<List<Registration>>();
            var effect = owner.effects().effect(() -> {
                var values = registerAllNow(providerInstanceId, copy);
                registrations.set(values);
                return new DrainingDisposable() {
                    private final Mono<Void> drained = Mono.defer(() -> revokeAll(values)).cache();
                    private final Mono<Void> disposed = drained.then(Mono.defer(afterDrain::dispose)).cache();

                    @Override
                    public Mono<Void> drain() {
                        return drained;
                    }

                    @Override
                    public Mono<Void> dispose() {
                        return disposed;
                    }
                };
            }, "contributions:" + providerInstanceId);
            return effect.ready().then(Mono.fromSupplier(() ->
                List.copyOf(registrations.get())));
        });
    }

    public ContributionDirectoryView current() {
        synchronized (monitor) {
            return viewUnsafe();
        }
    }

    public Flux<ContributionDirectoryView> views() {
        return views.asFlux();
    }

    public Mono<Void> closeAsync() {
        synchronized (monitor) {
            if (closing != null) {
                return closing;
            }
            closed = true;
            var current = List.copyOf(liveEntries);
            closing = revokeEntries(current)
                .doOnSuccess(ignored -> views.tryEmitComplete()).cache();
            return closing;
        }
    }

    @Override
    public void close() {
        closeAsync().block();
    }

    <D, I, O> ContributionCall<I, O> acquire(
        Map<ContributionId, Entry<?, ?, ?>> routes, ContributionKind<D, I, O> kind,
        ContributionId id) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        return new Call<>(acquireEntry(routes, kind, id), kind);
    }

    private <D, I, O> Entry<D, I, O> acquireEntry(
        Map<ContributionId, Entry<?, ?, ?>> routes, ContributionKind<D, I, O> kind,
        ContributionId id) {
        synchronized (monitor) {
            var raw = routes.get(id);
            if (closed || raw == null || !raw.accepting || raw.kind != kind) {
                throw new ContributionUnavailableException(id);
            }
            @SuppressWarnings("unchecked")
            var entry = (Entry<D, I, O>) raw;
            entry.inflight++;
            return entry;
        }
    }

    private List<Registration> registerAllNow(
        String providerInstanceId, List<ContributionBinding<?, ?, ?>> bindings) {
        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("contribution directory is closed");
            }
            var additions = new ArrayList<Entry<?, ?, ?>>();
            for (var binding : bindings) {
                var id = new ContributionId(providerInstanceId, binding.localName());
                if (entries.containsKey(id)
                    || additions.stream().anyMatch(entry -> entry.id.equals(id))) {
                    throw new IllegalArgumentException("duplicate contribution "
                        + id.providerInstanceId() + '/' + id.localName());
                }
                additions.add(entry(id, binding));
            }
            additions.forEach(entry -> entries.put(entry.id, entry));
            liveEntries.addAll(additions);
            publishUnsafe();
            return additions.stream().map(Registration::new).toList();
        }
    }

    private static <D, I, O> Entry<D, I, O> entry(
        ContributionId id, ContributionBinding<D, I, O> binding) {
        return new Entry<>(id, binding.kind(), binding.descriptor(), binding.handler());
    }

    private Mono<Void> revokeAll(List<Registration> registrations) {
        List<Entry<?, ?, ?>> values = new ArrayList<>();
        registrations.forEach(registration -> values.add(registration.entry));
        return revokeEntries(values);
    }

    private Mono<Void> revoke(Entry<?, ?, ?> entry) {
        return revokeEntries(List.of(entry));
    }

    private Mono<Void> revokeEntries(List<Entry<?, ?, ?>> values) {
        synchronized (monitor) {
            var changed = false;
            for (var entry : values) {
                if (entry.accepting) {
                    entry.accepting = false;
                    entries.remove(entry.id, entry);
                    changed = true;
                    completeIfDrained(entry);
                }
            }
            if (changed) {
                publishUnsafe();
            }
        }
        return awaitDrained(values);
    }

    private Mono<Void> awaitDrained(List<Entry<?, ?, ?>> values) {
        return Flux.fromIterable(values)
            .flatMap(entry -> entry.drained.asMono().materialize())
            .collectList()
            .flatMap(signals -> {
                Throwable first = null;
                for (var signal : signals) {
                    if (!signal.isOnError()) {
                        continue;
                    }
                    if (first == null) {
                        first = signal.getThrowable();
                    } else {
                        first.addSuppressed(signal.getThrowable());
                    }
                }
                return first == null ? Mono.<Void>empty() : Mono.<Void>error(first);
            });
    }

    private void release(Entry<?, ?, ?> entry, String cleanupFailure) {
        synchronized (monitor) {
            entry.inflight--;
            if (cleanupFailure != null && entry.cleanupFailure == null) {
                entry.cleanupFailure = cleanupFailure;
            }
            completeIfDrained(entry);
        }
    }

    private void completeIfDrained(Entry<?, ?, ?> entry) {
        if (!entry.accepting && entry.inflight == 0) {
            if (entry.cleanupFailure == null) {
                liveEntries.remove(entry);
                entry.drained.tryEmitEmpty();
            } else {
                entry.drained.tryEmitError(new ContributionDrainException(
                    entry.id, entry.cleanupFailure));
            }
        }
    }

    private void publishUnsafe() {
        revision++;
        views.tryEmitNext(viewUnsafe());
    }

    private ContributionDirectoryView viewUnsafe() {
        var values = entries.values().stream()
            .map(entry -> new ContributionSnapshotEntry(
                entry.id, entry.kind.name(), entry.descriptor))
            .sorted(Comparator.comparing(value ->
                value.id().providerInstanceId() + '\0' + value.id().localName()))
            .toList();
        return new ContributionDirectoryView(
            new ContributionSnapshot(revision, values),
            new ContributionRoutes(this, entries));
    }

    static final class Entry<D, I, O> {
        private final ContributionId id;
        private final ContributionKind<D, I, O> kind;
        private final D descriptor;
        private final ContributionHandler<I, O> handler;
        private final Sinks.One<Void> drained = Sinks.one();
        private boolean accepting = true;
        private int inflight;
        private String cleanupFailure;

        private Entry(ContributionId id, ContributionKind<D, I, O> kind,
                      D descriptor, ContributionHandler<I, O> handler) {
            this.id = id;
            this.kind = kind;
            this.descriptor = descriptor;
            this.handler = handler;
        }
    }

    private final class Call<I, O> implements ContributionCall<I, O> {
        private final Entry<?, I, O> entry;
        private final ContributionKind<?, I, O> kind;
        private boolean closed;
        private boolean invoked;

        private <D> Call(Entry<D, I, O> entry, ContributionKind<D, I, O> kind) {
            this.entry = entry;
            this.kind = kind;
        }

        @Override
        public Mono<O> invoke(Context caller, I input) {
            Objects.requireNonNull(caller, "caller");
            if (input != null && !kind.inputType().isInstance(input)) {
                return Mono.error(new IllegalArgumentException(
                    "input is not a " + kind.inputType().getName()));
            }
            return Mono.defer(() -> {
                claimInvocation();
                return Objects.requireNonNull(entry.handler.invoke(
                    InvocationContext.of(caller, "contribution:" + kind.name()), input),
                    "contribution handler returned null");
            }).map(output -> {
                if (output != null && !kind.outputType().isInstance(output)) {
                    throw new IllegalArgumentException(
                        "output is not a " + kind.outputType().getName());
                }
                return output;
            });
        }

        @Override
        public void failCleanup(String detail) {
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank()) {
                throw new IllegalArgumentException("detail must not be blank");
            }
            releaseOnce(detail);
        }

        @Override
        public void close() {
            releaseOnce(null);
        }

        private void claimInvocation() {
            synchronized (monitor) {
                if (closed) {
                    throw new IllegalStateException("contribution call is closed");
                }
                if (invoked) {
                    throw new IllegalStateException("contribution call was already invoked");
                }
                invoked = true;
            }
        }

        private void releaseOnce(String cleanupFailure) {
            synchronized (monitor) {
                if (closed) {
                    return;
                }
                closed = true;
                release(entry, cleanupFailure);
            }
        }
    }

    private final class Registration implements ContributionRegistration {
        private final Entry<?, ?, ?> entry;

        private Registration(Entry<?, ?, ?> entry) {
            this.entry = entry;
        }

        @Override
        public ContributionId id() {
            return entry.id;
        }

        @Override
        public Mono<Void> dispose() {
            return revoke(entry);
        }
    }
}
