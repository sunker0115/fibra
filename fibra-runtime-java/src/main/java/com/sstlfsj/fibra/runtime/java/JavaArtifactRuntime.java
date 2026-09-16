package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.PluginFacet;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.engine.ArtifactRuntime;
import com.sstlfsj.fibra.engine.DeploymentTargetCompiler.CompiledFacet;
import com.sstlfsj.fibra.engine.PreparedArtifact;
import com.sstlfsj.fibra.engine.PreparedArtifactUpdate;
import com.sstlfsj.fibra.engine.ResolvedFacetDependency;
import com.sstlfsj.fibra.engine.RuntimeArtifactInspection;
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

/** Java 静态制品与 classloader 的唯一所有者，不创建执行实例。 */
public final class JavaArtifactRuntime implements ArtifactRuntime {
    public static final RuntimeId RUNTIME_ID = new RuntimeId("java");
    private static final List<String> DEFAULT_PARENT_PACKAGES = List.of(
        "java.", "javax.", "jdk.", "sun.", "com.sstlfsj.fibra.",
        "reactor.", "org.reactivestreams.", "org.slf4j.");
    private final ClassLoader parent;
    private final List<String> parentPackages;
    private final LoaderCloser closer;
    private final JavaFacetDescriptorReader descriptors = new JavaFacetDescriptorReader();
    private final Map<ArtifactId, JavaPreparedArtifact> active = new LinkedHashMap<>();
    private long nextIdentity;
    private Update pending;
    private Mono<Void> close;

    public JavaArtifactRuntime() { this(JavaArtifactRuntime.class.getClassLoader(), DEFAULT_PARENT_PACKAGES); }
    public JavaArtifactRuntime(ClassLoader parent, List<String> parentPackages) {
        this(parent, parentPackages, PluginClassLoader::close);
    }
    JavaArtifactRuntime(ClassLoader parent, List<String> parentPackages, LoaderCloser closer) {
        this.parent = Objects.requireNonNull(parent, "parent");
        this.parentPackages = List.copyOf(parentPackages);
        this.closer = Objects.requireNonNull(closer, "closer");
    }

    @Override public RuntimeId id() { return RUNTIME_ID; }
    @Override public Mono<Void> probe(PluginFacet source) {
        return Mono.fromRunnable(() -> descriptors.read(source));
    }
    @Override public Mono<RuntimeArtifactInspection> inspect(ManagedFacet facet) {
        return Mono.fromCallable(() -> {
            var descriptor = descriptors.read(facet.facet());
            var metadata = new LinkedHashMap<String, Object>();
            descriptor.entrypoint().ifPresent(value -> metadata.put("entrypoint", value));
            return new RuntimeArtifactInspection(RUNTIME_ID, facet.artifactId(), metadata);
        });
    }
    @Override public synchronized PreparedArtifactUpdate createUpdate(List<CompiledFacet> target) {
        if (close != null) throw new IllegalStateException("Java artifact runtime is closed");
        if (pending != null) throw new IllegalStateException("Java artifact update is not cleaned up");
        pending = new Update(List.copyOf(target));
        return pending;
    }
    @Override public synchronized Snapshot snapshot() {
        var values = new ArrayList<>(active.values());
        if (pending != null) values.addAll(pending.owned().values());
        return new Snapshot(RUNTIME_ID, values.stream().map(JavaPreparedArtifact::resource).toList());
    }
    @Override public synchronized Mono<Void> closeAsync() {
        if (close == null) {
            if (pending != null) pending.closed = true;
            close = Mono.defer(() -> {
                synchronized (this) {
                    return pending == null ? Mono.empty() : pending.awaitPreparation();
                }
            }).then(Mono.<Void>fromRunnable(() -> {
                synchronized (this) {
                    if (pending != null) pending.releasePreparation();
                    var values = new ArrayList<>(active.values());
                    if (pending != null) values.addAll(pending.owned().values());
                    closeLoaded(values);
                    active.clear();
                    if (pending != null) pending.finishCleanup();
                    pending = null;
                }
            })).cache();
        }
        return close;
    }

    private final class Update implements PreparedArtifactUpdate {
        private final List<CompiledFacet> target;
        private final Map<ArtifactId, JavaPreparedArtifact> fresh = new LinkedHashMap<>();
        private final Map<ArtifactId, JavaPreparedArtifact> old = new LinkedHashMap<>();
        private Mono<Void> preparation = Mono.<Void>fromRunnable(this::prepare).cache();
        private Map<ArtifactId, PreparedArtifact> prepared;
        private Set<ArtifactId> affected = Set.of();
        private Mono<Void> cleanup;
        private boolean started;
        private boolean adopted;
        private boolean closed;

        private Update(List<CompiledFacet> target) { this.target = target; }

        @Override public Mono<Void> prepareAsync() {
            return Mono.defer(() -> {
                synchronized (JavaArtifactRuntime.this) {
                    if (closed || close != null) return Mono.error(new IllegalStateException("Java artifact update is closed"));
                    started = true;
                    return preparation;
                }
            });
        }

        private void prepare() {
            synchronized (JavaArtifactRuntime.this) {
                var graph = JavaFacetGraph.resolve(target);
                var next = new LinkedHashMap<ArtifactId, CompiledFacet>();
                var descriptions = new LinkedHashMap<ArtifactId, JavaFacetDescriptor>();
                for (var id : graph.dependencyFirst()) {
                    var facet = graph.facet(id);
                    next.put(id, facet);
                    descriptions.put(id, descriptors.read(facet.facet().facet()));
                    JavaClassIndex.validate(id, List.of(facet.facet().facet().payload()), parent, parentPackages);
                }
                affected = closure(active, next);
                var result = new LinkedHashMap<ArtifactId, PreparedArtifact>();
                try {
                    for (var id : graph.dependencyFirst()) {
                        if (!affected.contains(id)) {
                            result.put(id, active.get(id));
                            continue;
                        }
                        var facet = graph.facet(id);
                        var loader = new PluginClassLoader(List.of(facet.facet().facet().payload().toUri().toURL()), parent, parentPackages);
                        var loaded = new JavaPreparedArtifact(facet, descriptions.get(id), loader);
                        loaded.identity = "java:" + id.value() + ':' + facet.facet().packageRevision() + ':' + ++nextIdentity;
                        // 在 wiring / 类型检查之前登记，partial prepare 仍有明确释放责任。
                        fresh.put(id, loaded);
                        loaded.loaderDependencies = facet.dependencies().stream()
                            .map(ResolvedFacetDependency::artifactId).filter(next::containsKey)
                            .map(dependency -> fresh.containsKey(dependency) ? fresh.get(dependency) : active.get(dependency))
                            .map(value -> Objects.requireNonNull(value, "Java dependency loader")).toList();
                        loader.dependencies(loaded.loaderDependencies.stream().map(value -> value.loader).toList());
                        result.put(id, loaded);
                    }
                    for (var loaded : fresh.values()) {
                        JavaClassSpace.validate(loaded.facet().artifactId(), loaded.descriptor, loaded.loader);
                    }
                } catch (IOException failure) {
                    throw new JavaRuntimeException(JavaRuntimePhase.LOAD, null, "cannot create Java facet loader", failure);
                }
                prepared = Map.copyOf(result);
            }
        }

        @Override public Map<ArtifactId, PreparedArtifact> preparedArtifacts() {
            synchronized (JavaArtifactRuntime.this) {
                if (prepared == null) throw new IllegalStateException("Java artifact update is not prepared");
                return prepared;
            }
        }
        @Override public Set<ArtifactId> affectedArtifacts() {
            synchronized (JavaArtifactRuntime.this) { return affected; }
        }
        @Override public void adopt() {
            synchronized (JavaArtifactRuntime.this) {
                if (closed || adopted || prepared == null || close != null || pending != this) {
                    throw new IllegalStateException("Java artifact update cannot adopt");
                }
                for (var id : affected) {
                    var retired = active.remove(id);
                    if (retired != null) { retired.state = ResourceState.RETIRED; old.put(id, retired); }
                }
                fresh.values().forEach(value -> value.state = ResourceState.ACTIVE);
                active.putAll(fresh);
                adopted = true;
            }
        }
        @Override public Mono<Void> closeAsync() {
            synchronized (JavaArtifactRuntime.this) {
                if (cleanup == null) {
                    closed = true;
                    cleanup = Mono.defer(this::awaitPreparation).then(Mono.<Void>fromRunnable(() -> {
                        synchronized (JavaArtifactRuntime.this) {
                            releasePreparation();
                            closeLoaded(new ArrayList<>(owned().values()));
                            finishCleanup();
                            if (pending == this) pending = null;
                        }
                    })).cache();
                }
                return cleanup;
            }
        }
        private Mono<Void> awaitPreparation() {
            synchronized (JavaArtifactRuntime.this) {
                return started ? preparation.onErrorResume(ignored -> Mono.empty()) : Mono.empty();
            }
        }
        private Map<ArtifactId, JavaPreparedArtifact> owned() { return adopted ? old : fresh; }
        private void releasePreparation() {
            preparation = Mono.empty();
            prepared = null;
            if (adopted) fresh.clear();
        }
        private void finishCleanup() { fresh.clear(); old.clear(); }
    }

    private static Set<ArtifactId> closure(Map<ArtifactId, JavaPreparedArtifact> previous,
                                           Map<ArtifactId, CompiledFacet> target) {
        var changed = new LinkedHashSet<ArtifactId>();
        var ids = new LinkedHashSet<>(previous.keySet());
        ids.addAll(target.keySet());
        for (var id : ids) {
            var before = previous.get(id);
            var after = target.get(id);
            if (before == null || after == null || !before.facet().equals(after.facet())
                || !before.dependencies().equals(after.dependencies())) changed.add(id);
        }
        var reverse = new LinkedHashMap<ArtifactId, Set<ArtifactId>>();
        var all = new ArrayList<>(target.values());
        previous.values().forEach(value -> all.add(value.compiled));
        for (var facet : all) {
            for (var dependency : facet.dependencies()) {
                reverse.computeIfAbsent(dependency.artifactId(), ignored -> new LinkedHashSet<>()).add(facet.facet().artifactId());
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

    private void closeLoaded(List<JavaPreparedArtifact> values) {
        var owned = new LinkedHashSet<>(values);
        var order = new ArrayList<JavaPreparedArtifact>();
        var visited = new LinkedHashSet<JavaPreparedArtifact>();
        owned.forEach(value -> visit(value, owned, visited, order));
        var blocked = new LinkedHashSet<JavaPreparedArtifact>();
        var failures = new ArrayList<Throwable>();
        for (var value : order.reversed()) {
            if (blocked.contains(value) || value.state == ResourceState.CLOSED) continue;
            if (value.closeFailure == null) {
                try {
                    closer.close(value.loader);
                    value.state = ResourceState.CLOSED;
                    value.loader = null;
                    value.loaderDependencies = List.of();
                } catch (IOException | RuntimeException failure) {
                    value.closeFailure = failure;
                    value.state = ResourceState.CLOSE_FAILED;
                }
            }
            if (value.closeFailure != null) {
                failures.add(value.closeFailure);
                blockPrerequisites(value, blocked);
            }
        }
        if (!failures.isEmpty()) {
            var failure = new JavaRuntimeException(JavaRuntimePhase.CLOSE, null, "cannot close Java facet loaders", null);
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }
    private static void visit(JavaPreparedArtifact value, Set<JavaPreparedArtifact> owned,
                               Set<JavaPreparedArtifact> visited, List<JavaPreparedArtifact> order) {
        if (!visited.add(value)) return;
        value.loaderDependencies.stream().filter(owned::contains).forEach(dependency -> visit(dependency, owned, visited, order));
        order.add(value);
    }
    private static void blockPrerequisites(JavaPreparedArtifact value, Set<JavaPreparedArtifact> blocked) {
        for (var dependency : value.loaderDependencies) {
            if (blocked.add(dependency)) blockPrerequisites(dependency, blocked);
        }
    }

    /** 执行面可读取入口与 loader，loader 生命周期仍归本 runtime。 */
    public static final class JavaPreparedArtifact implements PreparedArtifact {
        private final CompiledFacet compiled;
        private final JavaFacetDescriptor descriptor;
        private volatile PluginClassLoader loader;
        private String identity;
        private List<JavaPreparedArtifact> loaderDependencies = List.of();
        private ResourceState state = ResourceState.PREPARED;
        private Throwable closeFailure;

        private JavaPreparedArtifact(CompiledFacet compiled, JavaFacetDescriptor descriptor, PluginClassLoader loader) {
            this.compiled = compiled;
            this.descriptor = descriptor;
            this.loader = loader;
        }
        @Override public ManagedFacet facet() { return compiled.facet(); }
        @Override public List<ResolvedFacetDependency> dependencies() { return compiled.dependencies(); }
        public JavaFacetDescriptor descriptor() { return descriptor; }
        public ClassLoader classLoader() {
            var current = loader;
            if (current == null) throw new IllegalStateException("Java facet loader is closed");
            return current;
        }
        private Resource resource() {
            return new Resource(facet(), identity, state, closeFailure == null ? null : closeFailure.toString());
        }
    }

    interface LoaderCloser { void close(PluginClassLoader loader) throws IOException; }
}
