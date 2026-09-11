package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.engine.PluginCatalog;
import com.sstlfsj.fibra.engine.PluginCatalogEntry;
import com.sstlfsj.fibra.engine.PluginRuntimeAdapter;
import com.sstlfsj.fibra.engine.RuntimeArtifactInspection;
import com.sstlfsj.fibra.engine.RuntimeCatalog;
import com.sstlfsj.fibra.engine.RuntimeResourceOwner;
import com.sstlfsj.fibra.engine.RuntimeResourceSnapshot;
import com.sstlfsj.fibra.engine.RuntimeResourceUpdate;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class JavaPluginRuntimeAdapter implements PluginRuntimeAdapter {
    public static final RuntimeId RUNTIME_ID = new RuntimeId("java");
    private static final List<String> DEFAULT_PARENT_PACKAGES = List.of(
        "java.", "javax.", "jdk.", "sun.", "com.sstlfsj.fibra.",
        "reactor.", "org.reactivestreams.", "org.slf4j.");

    private final ClassLoader parent;
    private final List<String> parentPackages;
    private final LoaderCloser closer;
    private final JavaManifestReader manifests = new JavaManifestReader();

    public JavaPluginRuntimeAdapter() {
        this(JavaPluginRuntimeAdapter.class.getClassLoader(), DEFAULT_PARENT_PACKAGES);
    }

    public JavaPluginRuntimeAdapter(ClassLoader parent, List<String> parentPackages) {
        this(parent, parentPackages, PluginClassLoader::close);
    }

    JavaPluginRuntimeAdapter(ClassLoader parent, List<String> parentPackages, LoaderCloser closer) {
        this.parent = Objects.requireNonNull(parent, "parent");
        this.parentPackages = List.copyOf(parentPackages);
        this.closer = Objects.requireNonNull(closer, "closer");
    }

    @Override
    public RuntimeId id() {
        return RUNTIME_ID;
    }

    @Override
    public Mono<RuntimeArtifactInspection> inspect(ArtifactRecord artifact) {
        return Mono.fromCallable(() -> {
            requireRuntime(artifact);
            var manifest = manifests.read(artifact);
            var attributes = new LinkedHashMap<String, Object>();
            manifest.entrypoint().ifPresent(value -> attributes.put("entrypoint", value));
            attributes.put("requires", manifest.requires().stream()
                .map(value -> value.artifactId().value()).toList());
            return new RuntimeArtifactInspection(RUNTIME_ID, artifact.id(), attributes);
        });
    }

    @Override
    public RuntimeResourceOwner create() {
        return new Owner();
    }

    private static void requireRuntime(ArtifactRecord artifact) {
        if (!RUNTIME_ID.equals(artifact.runtimeId())) {
            throw new IllegalArgumentException("artifact runtime is not Java");
        }
    }

    /** Owner 是所有权状态唯一的锁；Update 不持有另一把锁。 */
    private final class Owner implements RuntimeResourceOwner {
        private final Map<ArtifactId, Loaded> active = new LinkedHashMap<>();
        private RuntimeCatalog catalog = RuntimeCatalog.empty();
        private long nextIdentity;
        private Update pending;
        private Mono<Void> close;

        @Override
        public synchronized RuntimeResourceUpdate createUpdate(List<ArtifactRecord> target) {
            if (close != null) throw new IllegalStateException("Java runtime owner is closed");
            if (pending != null) throw new IllegalStateException("Java runtime update is not cleaned up");
            pending = new Update(this, target);
            return pending;
        }

        @Override
        public synchronized RuntimeCatalog catalog() {
            return catalog;
        }

        @Override
        public synchronized RuntimeResourceSnapshot snapshot() {
            var values = new ArrayList<Loaded>(active.values());
            if (pending != null) values.addAll(pending.owned().values());
            return snapshotOf(values);
        }

        @Override
        public synchronized Mono<Void> closeAsync() {
            if (close == null) {
                if (pending != null) pending.closed = true;
                close = Mono.<Void>defer(() -> {
                    Mono<Void> preparation;
                    synchronized (this) {
                        preparation = pending == null ? Mono.empty() : pending.awaitPreparation();
                    }
                    return preparation.then(Mono.<Void>fromRunnable(() -> {
                        synchronized (this) {
                            // 真实引用区分同一制品的新旧 loader，失败时只保护其实际先决资源。
                            var values = new ArrayList<Loaded>(active.values());
                            if (pending != null) values.addAll(pending.owned().values());
                            closeLoaded(values);
                            active.clear();
                            pending = null;
                            catalog = RuntimeCatalog.empty();
                        }
                    }));
                }).cache();
            }
            return close;
        }
    }

    private final class Update implements RuntimeResourceUpdate {
        private final Owner owner;
        private final List<ArtifactRecord> target;
        private final Map<ArtifactId, Loaded> fresh = new LinkedHashMap<>();
        private final Map<ArtifactId, Loaded> old = new LinkedHashMap<>();
        private final Mono<Void> preparation = Mono.<Void>fromRunnable(this::prepare).cache();
        private Set<ArtifactId> affected = Set.of();
        private RuntimeCatalog catalog;
        private Mono<Void> close;
        private boolean started;
        private boolean adopted;
        private boolean closed;

        private Update(Owner owner, List<ArtifactRecord> target) {
            this.owner = owner;
            this.target = List.copyOf(target);
        }

        @Override
        public Mono<Void> prepareAsync() {
            return Mono.defer(() -> {
                synchronized (owner) {
                    if (closed || owner.close != null) {
                        return Mono.error(new IllegalStateException("Java runtime update is closed"));
                    }
                    started = true;
                }
                return preparation;
            });
        }

        private void prepare() {
            var records = new LinkedHashMap<ArtifactId, ArtifactRecord>();
            var next = new LinkedHashMap<ArtifactId, JavaPluginManifest>();
            for (var record : target) {
                requireRuntime(record);
                if (records.putIfAbsent(record.id(), record) != null) {
                    throw new IllegalArgumentException("duplicate Java artifact " + record.id().value());
                }
                next.put(record.id(), manifests.read(record));
            }
            var graph = JavaArtifactGraph.resolve(next.values());
            Map<ArtifactId, Loaded> previous;
            synchronized (owner) {
                previous = new LinkedHashMap<>(owner.active);
                affected = closure(previous, records, next);
            }
            try {
                for (var id : graph.dependencyFirst()) {
                    if (!affected.contains(id)) continue;
                    var record = records.get(id);
                    var loader = new PluginClassLoader(record.location().toUri().toURL(), parent, parentPackages);
                    synchronized (owner) {
                        var loaded = new Loaded(record, next.get(id), loader,
                            "java:" + id.value() + ':' + record.revision() + ':' + ++owner.nextIdentity);
                        // 创建即登记；接线或 entrypoint 半失败也保留释放所有权。
                        fresh.put(id, loaded);
                        loaded.dependencies = next.get(id).requires().stream().map(requirement -> {
                            var dependency = requirement.artifactId();
                            return fresh.containsKey(dependency) ? fresh.get(dependency) : previous.get(dependency);
                        }).map(value -> Objects.requireNonNull(value, "Java dependency loader")).toList();
                        loader.dependencies(loaded.dependencies.stream().map(value -> value.loader).toList());
                    }
                }
                for (var id : graph.dependencyFirst()) {
                    Loaded loaded;
                    synchronized (owner) {
                        loaded = fresh.get(id);
                    }
                    if (loaded == null) continue;
                    var entry = JavaClassSpace.entry(id, loaded.manifest, loaded.loader);
                    synchronized (owner) {
                        loaded.entry = entry;
                    }
                }
            } catch (IOException failure) {
                throw new JavaRuntimeException(JavaRuntimePhase.LOAD, null, "cannot create Java plugin loader", failure);
            }
            synchronized (owner) {
                var entries = new ArrayList<PluginCatalogEntry<?>>();
                var byDefinition = new LinkedHashMap<String, ArtifactId>();
                for (var id : graph.dependencyFirst()) {
                    var loaded = fresh.containsKey(id) ? fresh.get(id) : previous.get(id);
                    if (loaded.entry == null) continue;
                    entries.add(loaded.entry);
                    byDefinition.put(loaded.entry.definition().name(), id);
                }
                catalog = new RuntimeCatalog(PluginCatalog.of(entries.toArray(PluginCatalogEntry[]::new)), byDefinition);
            }
        }

        @Override
        public Set<ArtifactId> affectedArtifacts() {
            synchronized (owner) {
                return affected;
            }
        }

        @Override
        public RuntimeCatalog catalog() {
            synchronized (owner) {
                if (catalog == null) throw new IllegalStateException("Java runtime update is not prepared");
                return catalog;
            }
        }

        @Override
        public RuntimeResourceSnapshot snapshot() {
            synchronized (owner) {
                return snapshotOf(new ArrayList<>(owned().values()));
            }
        }

        @Override
        public void adopt() {
            synchronized (owner) {
                if (closed || adopted || catalog == null || owner.close != null || owner.pending != this) {
                    throw new IllegalStateException("Java runtime update cannot adopt");
                }
                for (var id : affected) {
                    var replaced = owner.active.remove(id);
                    if (replaced != null) {
                        replaced.state = RuntimeResourceSnapshot.State.RETIRED;
                        old.put(id, replaced);
                    }
                }
                fresh.values().forEach(value -> value.state = RuntimeResourceSnapshot.State.ACTIVE);
                owner.active.putAll(fresh);
                owner.catalog = catalog;
                adopted = true;
            }
        }

        @Override
        public Mono<Void> closeAsync() {
            synchronized (owner) {
                if (close == null) {
                    closed = true;
                    close = Mono.defer(this::awaitPreparation).then(Mono.<Void>fromRunnable(() -> {
                        synchronized (owner) {
                            closeLoaded(new ArrayList<>(owned().values()));
                            if (owner.pending == this) owner.pending = null;
                        }
                    })).cache();
                }
                return close;
            }
        }

        private Mono<Void> awaitPreparation() {
            synchronized (owner) {
                return started ? preparation.onErrorResume(ignored -> Mono.empty()) : Mono.empty();
            }
        }

        private Map<ArtifactId, Loaded> owned() {
            return adopted ? old : fresh;
        }
    }

    private static Set<ArtifactId> closure(Map<ArtifactId, Loaded> old,
                                          Map<ArtifactId, ArtifactRecord> target,
                                          Map<ArtifactId, JavaPluginManifest> next) {
        var changed = new LinkedHashSet<ArtifactId>();
        var ids = new LinkedHashSet<>(old.keySet());
        ids.addAll(target.keySet());
        for (var id : ids) {
            var before = old.get(id);
            var after = target.get(id);
            if (!Objects.equals(before == null ? null : before.artifact.revision(),
                after == null ? null : after.revision())) changed.add(id);
        }
        var reverse = new LinkedHashMap<ArtifactId, Set<ArtifactId>>();
        var manifests = new ArrayList<>(next.values());
        old.values().forEach(value -> manifests.add(value.manifest));
        for (var manifest : manifests) {
            for (var requirement : manifest.requires()) {
                reverse.computeIfAbsent(requirement.artifactId(), ignored -> new LinkedHashSet<>())
                    .add(manifest.artifactId());
            }
        }
        var queue = new ArrayDeque<>(changed);
        while (!queue.isEmpty()) {
            for (var dependent : reverse.getOrDefault(queue.removeFirst(), Set.of())) {
                if (changed.add(dependent)) queue.add(dependent);
            }
        }
        return Set.copyOf(changed);
    }

    /** 按真实 loader 依赖排序；新旧制品 ID 相同也不会保护错版本。 */
    private void closeLoaded(List<Loaded> values) {
        var owned = new LinkedHashSet<>(values);
        var order = new ArrayList<Loaded>();
        var visited = new LinkedHashSet<Loaded>();
        owned.forEach(value -> visit(value, owned, visited, order));
        var blocked = new LinkedHashSet<Loaded>();
        var failures = new ArrayList<Throwable>();
        for (var value : order.reversed()) {
            if (blocked.contains(value) || value.state == RuntimeResourceSnapshot.State.CLOSED) continue;
            if (value.closeFailure == null) {
                try {
                    closer.close(value.loader);
                    value.state = RuntimeResourceSnapshot.State.CLOSED;
                } catch (IOException | RuntimeException failure) {
                    value.closeFailure = failure;
                    value.state = RuntimeResourceSnapshot.State.CLOSE_FAILED;
                }
            }
            if (value.closeFailure != null) {
                failures.add(value.closeFailure);
                blockPrerequisites(value, blocked);
            }
        }
        if (!failures.isEmpty()) {
            var failure = new JavaRuntimeException(JavaRuntimePhase.CLOSE, null, "cannot close Java plugin loaders", null);
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private static void visit(Loaded value, Set<Loaded> owned, Set<Loaded> visited, List<Loaded> order) {
        if (!visited.add(value)) return;
        value.dependencies.stream().filter(owned::contains)
            .forEach(dependency -> visit(dependency, owned, visited, order));
        order.add(value);
    }

    private static void blockPrerequisites(Loaded value, Set<Loaded> blocked) {
        for (var dependency : value.dependencies) {
            if (blocked.add(dependency)) blockPrerequisites(dependency, blocked);
        }
    }

    private static RuntimeResourceSnapshot snapshotOf(List<Loaded> values) {
        return new RuntimeResourceSnapshot(RUNTIME_ID, values.stream().map(Loaded::resource).toList());
    }

    private static final class Loaded {
        private final ArtifactRecord artifact;
        private final JavaPluginManifest manifest;
        private final PluginClassLoader loader;
        private final String identity;
        private List<Loaded> dependencies = List.of();
        private PluginCatalogEntry<?> entry;
        private RuntimeResourceSnapshot.State state = RuntimeResourceSnapshot.State.PREPARED;
        private Throwable closeFailure;

        private Loaded(ArtifactRecord artifact, JavaPluginManifest manifest, PluginClassLoader loader, String identity) {
            this.artifact = artifact;
            this.manifest = manifest;
            this.loader = loader;
            this.identity = identity;
        }

        private RuntimeResourceSnapshot.Resource resource() {
            return RuntimeResourceSnapshot.Resource.builder().artifact(artifact).identity(identity).state(state)
                .failure(closeFailure == null ? null : closeFailure.toString()).build();
        }
    }

    interface LoaderCloser {
        void close(PluginClassLoader loader) throws IOException;
    }
}
