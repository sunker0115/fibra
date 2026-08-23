package com.sstlfsj.fibra.loader.config;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Fibra;
import com.sstlfsj.fibra.PluginDescriptor;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.loader.pf4j.PluginConfigFactory;
import com.sstlfsj.fibra.loader.pf4j.PluginInstanceSpec;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** 把 YAML/JSON 配置树事务化同步到 PF4J 和 Fibra 运行实例。 */
public final class FibraConfigLoader implements AutoCloseable {
    private final Context root;
    private final FibraPluginLoader plugins;
    private final Path configPath;
    private final List<FibraConfigPatch> patches;
    private final ConfigTreeResolver resolver;
    private final ConfigDocumentWriter writer = new ConfigDocumentWriter();
    private final ConfigAtomicFileMover fileMover;
    private final Map<String, ManagedEntry> runtime = new LinkedHashMap<>();
    private final Map<SharedIsolate, Object> sharedIsolates = new LinkedHashMap<>();

    private FibraConfigSnapshot snapshot;
    private FibraConfigWatcher watcher;
    private FibraConfigWatcher closingWatcher;
    private boolean loaded;
    private volatile boolean closing;
    private volatile boolean closed;

    private FibraConfigLoader(Builder builder) {
        this.root = builder.root;
        this.plugins = builder.plugins;
        this.configPath = builder.configPath;
        this.patches = List.copyOf(builder.patches);
        this.resolver = new ConfigTreeResolver(builder.limits(), builder.warningSink);
        this.fileMover = builder.fileMover;
    }

    public static Builder builder(Context root, FibraPluginLoader plugins, Path configPath) {
        return new Builder(root, plugins, configPath);
    }

    public FibraConfigSnapshot load() {
        return plugins.runExclusive(() -> {
            requireOpen();
            if (loaded) {
                throw new IllegalStateException("FibraConfigLoader.load() may only be called once");
            }
            var candidate = resolver.resolve(configPath, patches);
            validateConfigs(candidate);
            reconcile(null, candidate);
            snapshot = candidate;
            loaded = true;
            return snapshot;
        });
    }

    public FibraConfigSnapshot refresh() {
        return plugins.runExclusive(() -> {
            requireLoaded();
            var candidate = resolver.resolve(configPath, patches);
            var changed = !snapshot.entries().equals(candidate.entries());
            if (!changed && runtimeMatches(candidate)) {
                return snapshot;
            }
            validateConfigs(candidate);
            reconcile(snapshot, candidate);
            if (changed) {
                snapshot = candidate;
            }
            return snapshot;
        });
    }

    public FibraConfigSnapshot snapshot() {
        return plugins.runExclusive(() -> {
            requireLoaded();
            return snapshot;
        });
    }

    public Optional<FibraConfigRuntimeEntry> resolve(String entryId) {
        Objects.requireNonNull(entryId, "entryId");
        return plugins.runExclusive(() -> {
            requireLoaded();
            var managed = runtime.get(entryId);
            return managed == null ? Optional.empty() : managed.publicEntry(plugins);
        });
    }

    public FibraConfigWatcher watch(Duration debounce,
                                    Consumer<FibraConfigReloadFailure> failureSink) {
        Objects.requireNonNull(debounce, "debounce");
        Objects.requireNonNull(failureSink, "failureSink");
        if (debounce.isNegative()) {
            throw new IllegalArgumentException("debounce must not be negative");
        }
        return plugins.runExclusive(() -> {
            synchronized (this) {
                requireLoaded();
                if (watcher != null) {
                    throw new IllegalStateException("Fibra config watcher is already running");
                }
                watcher = new FibraConfigWatcher(this, debounce, failureSink);
                return watcher;
            }
        });
    }

    public String create(String parentEntryId, int position, Map<String, ?> entry) {
        Objects.requireNonNull(entry, "entry");
        return plugins.runExclusive(() -> {
            requireLoaded();
            var documents = new MutableDocuments();
            var target = target(documents, parentEntryId);
            var created = mutableMap(entry);
            var idValue = created.get("id");
            if (!(idValue instanceof String id) || id.isBlank()) {
                throw new IllegalArgumentException("created entry id must be a non-blank string");
            }
            target.entries().add(insertionIndex(position, target.entries().size()), created);
            commitDocuments(documents.values());
            return parentEntryId == null ? id : parentEntryId + ':' + id;
        });
    }

    public void update(String entryId, FibraConfigPatch patch, String parentEntryId,
                       int position) {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(patch, "patch");
        plugins.runExclusive(() -> {
            requireLoaded();
            if (patch.operation() != FibraConfigPatch.Operation.OVERRIDE
                || !entryId.equals(patch.targetId())) {
                throw new IllegalArgumentException(
                    "update requires an override patch targeting " + entryId);
            }
            var existing = snapshot.resolve(entryId).orElseThrow(() ->
                new IllegalArgumentException("unknown config entry " + entryId));
            if (parentEntryId != null && (parentEntryId.equals(entryId)
                || parentEntryId.startsWith(entryId + ':'))) {
                throw failure(FibraConfigErrorStage.VALIDATE, existing,
                    "entry cannot be moved into itself or its descendant", null);
            }
            var documents = new MutableDocuments();
            var source = sourceLocation(documents, existing);
            var updated = source.entries().remove(source.index());
            if (patch.expectedPluginId() != null
                && !patch.expectedPluginId().equals(updated.get("name"))) {
                throw new IllegalArgumentException("patch expected plugin does not match "
                    + entryId);
            }
            patch.fields().forEach((name, value) -> {
                if (value == null) {
                    updated.remove(name);
                } else {
                    updated.put(name, LiteralValues.mutable(value));
                }
            });
            var target = target(documents, parentEntryId);
            target.entries().add(insertionIndex(position, target.entries().size()), updated);
            commitDocuments(documents.values());
        });
    }

    public void remove(String entryId) {
        Objects.requireNonNull(entryId, "entryId");
        plugins.runExclusive(() -> {
            requireLoaded();
            var existing = snapshot.resolve(entryId).orElseThrow(() ->
                new IllegalArgumentException("unknown config entry " + entryId));
            var documents = new MutableDocuments();
            var source = sourceLocation(documents, existing);
            source.entries().remove(source.index());
            commitDocuments(documents.values());
        });
    }

    @Override
    public void close() {
        FibraConfigWatcher currentWatcher;
        synchronized (this) {
            while (closing && !closed) {
                if (closingWatcher != null && closingWatcher.isWorkerThread()) {
                    return;
                }
                try {
                    wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                        "interrupted while waiting for Fibra config loader close", exception);
                }
            }
            if (closed) {
                return;
            }
            closing = true;
            currentWatcher = watcher;
            watcher = null;
            closingWatcher = currentWatcher;
        }
        var succeeded = false;
        try {
            if (currentWatcher != null) {
                currentWatcher.close();
            }
            plugins.runExclusive(() -> {
                var entries = new ArrayList<>(runtime.values());
                for (int index = entries.size() - 1; index >= 0; index--) {
                    dispose(entries.get(index));
                }
                runtime.clear();
                sharedIsolates.clear();
            });
            succeeded = true;
        } finally {
            synchronized (this) {
                closed = succeeded;
                closing = false;
                closingWatcher = null;
                notifyAll();
            }
        }
    }

    Path configPath() {
        return configPath;
    }

    Set<Path> watchedPaths() {
        return plugins.runExclusive(() -> {
            requireLoaded();
            var result = new LinkedHashSet<Path>();
            result.add(snapshot.rootPath());
            snapshot.allEntries().forEach(entry -> result.add(entry.source()));
            result.addAll(resolver.attemptedPaths());
            return Set.copyOf(result);
        });
    }

    synchronized void watcherClosed(FibraConfigWatcher candidate) {
        if (watcher == candidate) {
            watcher = null;
        }
    }

    private void validateConfigs(FibraConfigSnapshot candidate) {
        for (var entry : candidate.allEntries()) {
            if (entry.kind() != FibraConfigEntry.Kind.PLUGIN) {
                continue;
            }
            Class<?> type;
            try {
                type = plugins.configType(entry.pluginId());
            } catch (RuntimeException exception) {
                throw failure(FibraConfigErrorStage.RESOLVE, entry,
                    "cannot resolve plugin " + entry.pluginId(), exception);
            }
            convert(entry, type);
        }
    }

    private static PluginConfigFactory configFactory(FibraConfigEntry entry) {
        return type -> convert(entry, type);
    }

    private static Object convert(FibraConfigEntry entry, Class<?> type) {
        try {
            if (type == Void.class) {
                if (entry.config() != null) {
                    throw new IllegalArgumentException("Void plugin config must be null");
                }
                return null;
            }
            return JsonMapper.builder().build().convertValue(entry.config(), type);
        } catch (RuntimeException exception) {
            throw failure(FibraConfigErrorStage.CONVERT, entry,
                "cannot convert config for " + entry.entryId(), exception);
        }
    }

    private void commitDocuments(Map<Path, List<Map<String, Object>>> documents) {
        var staged = new ArrayList<StagedDocument>();
        try {
            for (var entry : documents.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
                var path = entry.getKey();
                if (!Files.isWritable(path)) {
                    throw new IOException("config file is not writable: " + path);
                }
                var bytes = writer.write(path, entry.getValue());
                resolver.validateSerialized(path, bytes);
                var temporary = Files.createTempFile(path.getParent(), ".fibra-config-", ".tmp");
                try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    var buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
                staged.add(new StagedDocument(path, temporary, Files.readAllBytes(path)));
            }
        } catch (RuntimeException | IOException exception) {
            deleteStaged(staged);
            throw new FibraConfigException(FibraConfigErrorStage.WRITE,
                "cannot stage config write", configPath, null, null, exception);
        }

        FibraConfigSnapshot candidate;
        try {
            candidate = resolver.resolve(configPath, patches, documents);
            validateConfigs(candidate);
            reconcile(snapshot, candidate);
        } catch (RuntimeException exception) {
            deleteStaged(staged);
            throw exception;
        }

        var replaced = new ArrayList<StagedDocument>();
        try {
            for (var document : staged) {
                fileMover.move(document.temporary(), document.path());
                replaced.add(document);
            }
            snapshot = candidate;
        } catch (IOException writeFailure) {
            var wrapped = new FibraConfigException(FibraConfigErrorStage.WRITE,
                "cannot atomically replace config file", configPath, null, null,
                writeFailure);
            var rollbackFailures = restoreFiles(replaced);
            try {
                reconcile(candidate, snapshot);
            } catch (RuntimeException rollbackFailure) {
                rollbackFailures.add(rollbackFailure);
            } finally {
                deleteStaged(staged);
            }
            if (!rollbackFailures.isEmpty()) {
                var rollback = new FibraConfigException(FibraConfigErrorStage.ROLLBACK,
                    "cannot roll back after config write failure", configPath,
                    null, null, wrapped);
                rollbackFailures.forEach(rollback::addSuppressed);
                throw rollback;
            }
            throw wrapped;
        } finally {
            deleteStaged(staged);
        }
    }

    private Target target(MutableDocuments documents, String parentEntryId) {
        if (parentEntryId == null) {
            var path = snapshot.rootPath();
            return new Target(documents.document(path));
        }
        var parent = snapshot.resolve(parentEntryId).orElseThrow(() ->
            new IllegalArgumentException("unknown parent entry " + parentEntryId));
        if (parent.kind() == FibraConfigEntry.Kind.PLUGIN) {
            throw new IllegalArgumentException("parent entry is not a group " + parentEntryId);
        }
        if (parent.kind() == FibraConfigEntry.Kind.INCLUDE) {
            return new Target(documents.document(parent.includedPath()));
        }
        var document = documents.document(parent.source());
        var group = find(document, parent.sourceEntryId());
        var config = group.get("config");
        if (config == null) {
            var children = new ArrayList<Map<String, Object>>();
            group.put("config", children);
            return new Target(children);
        }
        if (!(config instanceof List<?> raw)) {
            throw new IllegalStateException("group config is not an array " + parentEntryId);
        }
        @SuppressWarnings("unchecked")
        var children = (List<Map<String, Object>>) raw;
        return new Target(children);
    }

    private static SourceLocation sourceLocation(MutableDocuments documents,
                                                 FibraConfigEntry entry) {
        try {
            var document = documents.document(entry.source());
            var entries = containingEntries(document, entry.sourceEntryId());
            return new SourceLocation(entries, indexOf(entries, entry.id()));
        } catch (IllegalStateException exception) {
            throw failure(FibraConfigErrorStage.VALIDATE, entry,
                "entry is generated by a patch and has no writable source node", exception);
        }
    }

    private static Map<String, Object> find(List<Map<String, Object>> document,
                                            String sourceEntryId) {
        var entries = document;
        Map<String, Object> current = null;
        var parts = sourceEntryId.split(":");
        for (int index = 0; index < parts.length; index++) {
            var part = parts[index];
            current = entries.stream()
                .filter(entry -> part.equals(entry.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "file-backed entry no longer exists " + sourceEntryId));
            if (index < parts.length - 1) {
                var config = current.get("config");
                if (!(config instanceof List<?> raw)) {
                    throw new IllegalStateException("entry path is not a group "
                        + sourceEntryId);
                }
                @SuppressWarnings("unchecked")
                var children = (List<Map<String, Object>>) raw;
                entries = children;
            }
        }
        return current;
    }

    private static List<Map<String, Object>> containingEntries(
        List<Map<String, Object>> document, String sourceEntryId) {
        var separator = sourceEntryId.lastIndexOf(':');
        if (separator < 0) {
            return document;
        }
        var parent = find(document, sourceEntryId.substring(0, separator));
        var config = parent.get("config");
        if (!(config instanceof List<?> raw)) {
            throw new IllegalStateException("entry parent is not a group " + sourceEntryId);
        }
        @SuppressWarnings("unchecked")
        var children = (List<Map<String, Object>>) raw;
        return children;
    }

    private static int indexOf(List<Map<String, Object>> entries, String id) {
        for (int index = 0; index < entries.size(); index++) {
            if (id.equals(entries.get(index).get("id"))) {
                return index;
            }
        }
        throw new IllegalStateException("file-backed entry no longer exists " + id);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableMap(Map<String, ?> value) {
        return (Map<String, Object>) LiteralValues.mutable(value);
    }

    private static int insertionIndex(int requested, int size) {
        return requested < 0 ? size : Math.min(requested, size);
    }

    private List<Throwable> restoreFiles(List<StagedDocument> replaced) {
        var failures = new ArrayList<Throwable>();
        for (int index = replaced.size() - 1; index >= 0; index--) {
            var document = replaced.get(index);
            Path temporary = null;
            try {
                temporary = Files.createTempFile(document.path().getParent(),
                    ".fibra-restore-", ".tmp");
                try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    var buffer = ByteBuffer.wrap(document.original());
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
                fileMover.move(temporary, document.path());
                temporary = null;
            } catch (IOException restoreFailure) {
                failures.add(restoreFailure);
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException cleanupFailure) {
                        failures.add(cleanupFailure);
                    }
                }
            }
        }
        return failures;
    }

    private static void deleteStaged(List<StagedDocument> staged) {
        for (var document : staged) {
            try {
                Files.deleteIfExists(document.temporary());
            } catch (IOException ignored) {
                // The primary operation already owns the observable failure.
            }
        }
    }

    private void reconcile(FibraConfigSnapshot previous, FibraConfigSnapshot candidate) {
        var previousEntries = index(previous);
        var candidateEntries = index(candidate);
        var replacements = replacements(previousEntries, candidateEntries);
        var journal = new ArrayDeque<Runnable>();

        try {
            if (previous != null) {
                var oldOrder = previous.allEntries();
                for (int index = oldOrder.size() - 1; index >= 0; index--) {
                    var oldEntry = oldOrder.get(index);
                    var nextEntry = candidateEntries.get(oldEntry.entryId());
                    if (mustUnmount(oldEntry, nextEntry, replacements)) {
                        var removed = runtime.remove(oldEntry.entryId());
                        if (removed != null) {
                            dispose(removed);
                            journal.addLast(() -> restore(removed));
                        }
                    }
                }
            }

            if (previous != null) {
                for (var nextEntry : candidate.allEntries()) {
                    var oldEntry = previousEntries.get(nextEntry.entryId());
                    if (oldEntry == null || replacements.contains(nextEntry.entryId())
                        || nextEntry.kind() != FibraConfigEntry.Kind.PLUGIN
                        || oldEntry.disabled() || nextEntry.disabled()
                        || Objects.equals(oldEntry.config(), nextEntry.config())) {
                        continue;
                    }
                    update(oldEntry, nextEntry, journal);
                }
            }

            for (var nextEntry : candidate.allEntries()) {
                var oldEntry = previousEntries.get(nextEntry.entryId());
                if (mustMount(oldEntry, nextEntry, replacements)
                    || shouldMount(nextEntry) && !isMounted(nextEntry)) {
                    var mounted = mount(nextEntry);
                    if (mounted != null) {
                        runtime.put(nextEntry.entryId(), mounted);
                        journal.addLast(() -> {
                            var current = runtime.remove(nextEntry.entryId());
                            if (current != null) {
                                dispose(current);
                            }
                        });
                    }
                }
            }
            for (var managed : runtime.values()) {
                managed.fibra(plugins).ifPresent(fibra -> fibra.await().block());
            }
        } catch (FibraConfigException failure) {
            rollback(journal, failure);
            throw failure;
        } catch (RuntimeException failure) {
            var wrapped = new FibraConfigException(FibraConfigErrorStage.APPLY,
                "cannot apply config candidate", candidate.rootPath(), null, null, failure);
            rollback(journal, wrapped);
            throw wrapped;
        }
    }

    private void update(FibraConfigEntry oldEntry, FibraConfigEntry nextEntry,
                        ArrayDeque<Runnable> journal) {
        var old = runtime.get(oldEntry.entryId());
        if (old == null) {
            throw failure(FibraConfigErrorStage.APPLY, nextEntry,
                "missing mounted entry " + nextEntry.entryId(), null);
        }
        try {
            var fibra = plugins.updateWithFactory(nextEntry.entryId(), configFactory(nextEntry));
            var updated = ManagedEntry.plugin(nextEntry);
            runtime.put(nextEntry.entryId(), updated);
            journal.addLast(() -> {
                plugins.updateWithFactory(oldEntry.entryId(), configFactory(oldEntry));
                runtime.put(oldEntry.entryId(), ManagedEntry.plugin(oldEntry));
            });
        } catch (RuntimeException failure) {
            var wrapped = failure(FibraConfigErrorStage.APPLY, nextEntry,
                "cannot update " + nextEntry.entryId(), failure);
            try {
                plugins.updateWithFactory(oldEntry.entryId(), configFactory(oldEntry));
                runtime.put(oldEntry.entryId(), ManagedEntry.plugin(oldEntry));
            } catch (RuntimeException rollbackFailure) {
                var rollback = failure(FibraConfigErrorStage.ROLLBACK, oldEntry,
                    "cannot roll back " + oldEntry.entryId(), wrapped);
                rollback.addSuppressed(rollbackFailure);
                throw rollback;
            }
            throw wrapped;
        }
    }

    private ManagedEntry mount(FibraConfigEntry entry) {
        if (entry.kind() == FibraConfigEntry.Kind.PLUGIN && entry.disabled()) {
            return null;
        }
        var parent = parentContext(entry.entryId());
        var decorated = decorate(parent, entry);
        try {
            Fibra fibra;
            if (entry.kind() == FibraConfigEntry.Kind.PLUGIN) {
                var builder = PluginInstanceSpec.builder(entry.entryId(), entry.pluginId())
                    .parentContext(decorated)
                    .configFactory(configFactory(entry))
                    .requirements(entry.inject());
                fibra = plugins.mount(builder.build());
            } else {
                fibra = decorated.plugin(PluginDescriptor.<Void>builder(entry.entryId()).build(),
                    (context, ignored) -> Mono.empty(), null);
                fibra.await().block();
            }
            return entry.kind() == FibraConfigEntry.Kind.PLUGIN
                ? ManagedEntry.plugin(entry)
                : ManagedEntry.scope(entry, fibra);
        } catch (RuntimeException exception) {
            throw failure(FibraConfigErrorStage.APPLY, entry,
                "cannot mount " + entry.entryId(), exception);
        }
    }

    private void restore(ManagedEntry removed) {
        var mounted = mount(removed.entry());
        if (mounted != null) {
            runtime.put(removed.entry().entryId(), mounted);
        }
    }

    private void dispose(ManagedEntry entry) {
        try {
            if (entry.entry().kind() == FibraConfigEntry.Kind.PLUGIN) {
                plugins.unmount(entry.entry().entryId());
            } else {
                entry.scopeFibra().dispose().block();
            }
        } catch (RuntimeException exception) {
            throw failure(FibraConfigErrorStage.DISPOSE, entry.entry(),
                "cannot dispose " + entry.entry().entryId(), exception);
        }
    }

    private Context parentContext(String entryId) {
        var separator = entryId.lastIndexOf(':');
        if (separator < 0) {
            return root;
        }
        var parentId = entryId.substring(0, separator);
        var parent = runtime.get(parentId);
        if (parent == null) {
            throw new IllegalStateException("missing parent runtime entry " + parentId);
        }
        return parent.context(plugins).orElseThrow(() ->
            new IllegalStateException("missing parent runtime context " + parentId));
    }

    private Context decorate(Context parent, FibraConfigEntry entry) {
        var result = parent;
        for (var isolate : entry.localIsolate().entrySet()) {
            Object label = Boolean.TRUE.equals(isolate.getValue())
                ? new Object()
                : sharedIsolates.computeIfAbsent(
                    new SharedIsolate(isolate.getKey(), (String) isolate.getValue()),
                    ignored -> new Object());
            result = result.isolate(isolate.getKey(), label);
        }
        for (var intercept : entry.localIntercept().entrySet()) {
            result = result.intercept(intercept.getKey(), intercept.getValue());
        }
        return result;
    }

    private static boolean mustUnmount(FibraConfigEntry oldEntry, FibraConfigEntry nextEntry,
                                       Set<String> replacements) {
        if (oldEntry.kind() == FibraConfigEntry.Kind.PLUGIN && oldEntry.disabled()) {
            return false;
        }
        return nextEntry == null || replacements.contains(oldEntry.entryId())
            || oldEntry.kind() == FibraConfigEntry.Kind.PLUGIN && nextEntry.disabled();
    }

    private static boolean mustMount(FibraConfigEntry oldEntry, FibraConfigEntry nextEntry,
                                     Set<String> replacements) {
        if (nextEntry.kind() == FibraConfigEntry.Kind.PLUGIN && nextEntry.disabled()) {
            return false;
        }
        return oldEntry == null || replacements.contains(nextEntry.entryId())
            || oldEntry.kind() == FibraConfigEntry.Kind.PLUGIN && oldEntry.disabled();
    }

    private static boolean shouldMount(FibraConfigEntry entry) {
        return entry.kind() != FibraConfigEntry.Kind.PLUGIN || !entry.disabled();
    }

    private boolean isMounted(FibraConfigEntry entry) {
        var managed = runtime.get(entry.entryId());
        return managed != null && managed.fibra(plugins).isPresent();
    }

    private boolean runtimeMatches(FibraConfigSnapshot candidate) {
        var expected = candidate.allEntries().stream()
            .filter(FibraConfigLoader::shouldMount)
            .map(FibraConfigEntry::entryId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!runtime.keySet().equals(expected)) {
            return false;
        }
        return candidate.allEntries().stream()
            .filter(FibraConfigLoader::shouldMount)
            .allMatch(this::isMounted);
    }

    private static Set<String> replacements(Map<String, FibraConfigEntry> previous,
                                            Map<String, FibraConfigEntry> candidate) {
        var result = new LinkedHashSet<String>();
        for (var entry : candidate.values()) {
            var old = previous.get(entry.entryId());
            var parentId = parentId(entry.entryId());
            if (old != null && (result.contains(parentId) || !sameBoundary(old, entry))) {
                result.add(entry.entryId());
            }
        }
        return result;
    }

    private static boolean sameBoundary(FibraConfigEntry first, FibraConfigEntry second) {
        return first.kind() == second.kind()
            && Objects.equals(first.pluginId(), second.pluginId())
            && Objects.equals(first.includedPath(), second.includedPath())
            && first.inject().equals(second.inject())
            && first.localIsolate().equals(second.localIsolate())
            && first.localIntercept().equals(second.localIntercept());
    }

    private static String parentId(String entryId) {
        var separator = entryId.lastIndexOf(':');
        return separator < 0 ? null : entryId.substring(0, separator);
    }

    private static Map<String, FibraConfigEntry> index(FibraConfigSnapshot snapshot) {
        var result = new LinkedHashMap<String, FibraConfigEntry>();
        if (snapshot != null) {
            snapshot.allEntries().forEach(entry -> result.put(entry.entryId(), entry));
        }
        return result;
    }

    private static void rollback(ArrayDeque<Runnable> journal,
                                 FibraConfigException failure) {
        var rollbackFailures = new ArrayList<Throwable>();
        while (!journal.isEmpty()) {
            try {
                journal.removeLast().run();
            } catch (RuntimeException rollbackFailure) {
                rollbackFailures.add(rollbackFailure);
            }
        }
        if (!rollbackFailures.isEmpty()) {
            var rollback = new FibraConfigException(FibraConfigErrorStage.ROLLBACK,
                "config rollback failed", failure.path(), failure.entryId(),
                failure.pluginId(), failure);
            rollbackFailures.forEach(rollback::addSuppressed);
            throw rollback;
        }
    }

    private static FibraConfigException failure(FibraConfigErrorStage stage,
                                                FibraConfigEntry entry,
                                                String message, Throwable cause) {
        return new FibraConfigException(stage, message, entry.source(), entry.entryId(),
            entry.pluginId(), cause);
    }

    private void requireLoaded() {
        requireOpen();
        if (!loaded) {
            throw new IllegalStateException("FibraConfigLoader.load() has not completed");
        }
    }

    private void requireOpen() {
        if (closing || closed) {
            throw new IllegalStateException("FibraConfigLoader is closed");
        }
    }

    private record ManagedEntry(FibraConfigEntry entry, Fibra scopeFibra) {
        static ManagedEntry plugin(FibraConfigEntry entry) {
            return new ManagedEntry(entry, null);
        }

        static ManagedEntry scope(FibraConfigEntry entry, Fibra fibra) {
            return new ManagedEntry(entry, fibra);
        }

        Optional<Fibra> fibra(FibraPluginLoader plugins) {
            return entry.kind() == FibraConfigEntry.Kind.PLUGIN
                ? plugins.fibra(entry.entryId())
                : Optional.of(scopeFibra);
        }

        Optional<Context> context(FibraPluginLoader plugins) {
            return fibra(plugins).map(Fibra::context);
        }

        Optional<FibraConfigRuntimeEntry> publicEntry(FibraPluginLoader plugins) {
            return fibra(plugins).map(fibra ->
                new FibraConfigRuntimeEntry(entry, fibra, fibra.context()));
        }
    }

    private record SharedIsolate(String serviceName, String label) {
    }

    private record Target(List<Map<String, Object>> entries) {
    }

    private record SourceLocation(List<Map<String, Object>> entries, int index) {
    }

    private record StagedDocument(Path path, Path temporary, byte[] original) {
    }

    private final class MutableDocuments {
        private final Map<Path, List<Map<String, Object>>> values = new LinkedHashMap<>();

        List<Map<String, Object>> document(Path source) {
            var path = source.toAbsolutePath().normalize();
            return values.computeIfAbsent(path, ignored -> mutableEntries(resolver.read(path)));
        }

        Map<Path, List<Map<String, Object>>> values() {
            return values;
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> mutableEntries(List<Map<String, Object>> entries) {
            return (List<Map<String, Object>>) (List<?>) LiteralValues.mutable(entries);
        }
    }

    public static final class Builder {
        private final Context root;
        private final FibraPluginLoader plugins;
        private final Path configPath;
        private final List<FibraConfigPatch> patches = new ArrayList<>();
        private Consumer<FibraConfigWarning> warningSink = ignored -> { };
        private long maxFileBytes = 4L * 1024 * 1024;
        private int maxDepth = 100;
        private int maxStringLength = 1024 * 1024;
        private int maxEntriesPerFile = 10_000;
        private ConfigAtomicFileMover fileMover = (source, target) -> Files.move(source, target,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        private Builder(Context root, FibraPluginLoader plugins, Path configPath) {
            this.root = Objects.requireNonNull(root, "root");
            if (root.root() != root) {
                throw new IllegalArgumentException("root must be the Fibra root Context");
            }
            this.plugins = Objects.requireNonNull(plugins, "plugins");
            this.configPath = Objects.requireNonNull(configPath, "configPath")
                .toAbsolutePath().normalize();
        }

        public Builder patches(List<FibraConfigPatch> values) {
            patches.clear();
            patches.addAll(Objects.requireNonNull(values, "patches"));
            return this;
        }

        public Builder warningSink(Consumer<FibraConfigWarning> value) {
            warningSink = Objects.requireNonNull(value, "warningSink");
            return this;
        }

        public Builder maxFileBytes(long value) {
            maxFileBytes = value;
            return this;
        }

        public Builder maxDepth(int value) {
            maxDepth = value;
            return this;
        }

        public Builder maxStringLength(int value) {
            maxStringLength = value;
            return this;
        }

        public Builder maxEntriesPerFile(int value) {
            maxEntriesPerFile = value;
            return this;
        }

        Builder fileMover(ConfigAtomicFileMover value) {
            fileMover = Objects.requireNonNull(value, "fileMover");
            return this;
        }

        public FibraConfigLoader build() {
            limits();
            return new FibraConfigLoader(this);
        }

        private ConfigLimits limits() {
            return new ConfigLimits(maxFileBytes, maxDepth, maxStringLength,
                maxEntriesPerFile);
        }
    }
}

@FunctionalInterface
interface ConfigAtomicFileMover {
    void move(Path source, Path target) throws IOException;
}
