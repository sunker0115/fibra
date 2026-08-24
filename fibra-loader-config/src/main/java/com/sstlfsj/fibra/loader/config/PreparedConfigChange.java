package com.sstlfsj.fibra.loader.config;

import com.sstlfsj.fibra.loader.pf4j.FibraPluginCatalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

final class PreparedConfigChange implements FibraConfigChange {
    private final FibraConfigLoader loader;
    private final FibraConfigSnapshot previous;
    private final FibraConfigSnapshot target;
    private final Path workspace;
    private final List<PreparedFile> files;

    private State state = State.PREPARED;
    private boolean filesCommitted;
    private boolean runtimeCommitted;

    private PreparedConfigChange(FibraConfigLoader loader, FibraConfigSnapshot previous,
                                 FibraConfigSnapshot target, Path workspace,
                                 List<PreparedFile> files) {
        this.loader = loader;
        this.previous = previous;
        this.target = target;
        this.workspace = workspace;
        this.files = files;
    }

    static PreparedConfigChange current(FibraConfigLoader loader,
                                        FibraPluginCatalog catalog, Path workspace) {
        var normalized = validateWorkspace(workspace);
        try {
            var target = loader.resolveAgainst(catalog);
            return new PreparedConfigChange(loader, loader.currentSnapshot(), target,
                normalized, List.of());
        } catch (RuntimeException failure) {
            cleanup(normalized, failure);
            throw failure;
        }
    }

    static PreparedConfigChange replacement(FibraConfigLoader loader, Path stagedConfig,
                                            FibraPluginCatalog catalog, Path workspace) {
        var normalized = validateWorkspace(workspace);
        try {
            var requested = Objects.requireNonNull(stagedConfig, "stagedConfig")
                .toAbsolutePath().normalize();
            var requestedBase = Objects.requireNonNull(requested.getParent(),
                "stagedConfig parent");
            validateStagedTree(requestedBase);
            var staged = requested.toRealPath();
            var stagedBase = requestedBase.toRealPath();
            var resolved = loader.resolvePath(staged);
            var target = remap(resolved, staged, stagedBase, loader.configPath(),
                loader.configPath().getParent());
            loader.validateAgainst(target, catalog);
            var files = prepareFiles(loader, resolved, staged, stagedBase, normalized);
            return new PreparedConfigChange(loader, loader.currentSnapshot(), target,
                normalized, files);
        } catch (IOException failure) {
            var wrapped = new FibraConfigException(FibraConfigErrorStage.READ,
                "cannot resolve staged config tree", stagedConfig, null, null, failure);
            cleanup(normalized, wrapped);
            throw wrapped;
        } catch (RuntimeException failure) {
            cleanup(normalized, failure);
            throw failure;
        }
    }

    @Override
    public FibraConfigSnapshot targetSnapshot() {
        requireUsable();
        return target;
    }

    @Override
    public void commit() {
        requireState(State.PREPARED, "commit");
        state = State.COMMITTING;
        try {
            replaceWithNext();
            filesCommitted = !files.isEmpty();
            loader.applySnapshot(previous, target);
            runtimeCommitted = true;
            loader.publishSnapshot(target);
            state = State.COMMITTED;
        } catch (RuntimeException failure) {
            throw failure;
        }
    }

    @Override
    public void complete() {
        requireState(State.COMMITTED, "complete");
        cleanupWorkspace();
        state = State.COMPLETED;
    }

    @Override
    public void rollback() {
        if (state == State.ROLLED_BACK || state == State.COMPLETED) {
            return;
        }
        var failures = new ArrayList<Throwable>();
        if (runtimeCommitted) {
            try {
                loader.applySnapshot(target, previous);
                loader.publishSnapshot(previous);
            } catch (RuntimeException failure) {
                failures.add(failure);
            }
        }
        if (filesCommitted) {
            restorePrevious(failures);
        }
        if (failures.isEmpty()) {
            try {
                cleanupWorkspace();
                state = State.ROLLED_BACK;
                return;
            } catch (RuntimeException failure) {
                failures.add(failure);
            }
        }
        var rollback = new FibraConfigException(FibraConfigErrorStage.ROLLBACK,
            "cannot roll back prepared config change", loader.configPath(), null, null,
            null);
        failures.forEach(rollback::addSuppressed);
        throw rollback;
    }

    @Override
    public void close() {
        if (state != State.COMPLETED && state != State.ROLLED_BACK) {
            rollback();
        }
    }

    private void replaceWithNext() {
        var replaced = new ArrayList<PreparedFile>();
        try {
            for (var file : files) {
                loader.replaceFile(file.next(), file.target());
                replaced.add(file);
            }
        } catch (RuntimeException failure) {
            restorePrevious(replaced, failure);
            throw failure;
        }
    }

    private void restorePrevious(List<Throwable> failures) {
        for (int index = files.size() - 1; index >= 0; index--) {
            var file = files.get(index);
            try {
                if (file.existed()) {
                    loader.replaceFile(file.previous(), file.target());
                } else {
                    Files.deleteIfExists(file.target());
                }
            } catch (RuntimeException | IOException failure) {
                failures.add(failure);
            }
        }
    }

    private void restorePrevious(List<PreparedFile> replaced, RuntimeException original) {
        var failures = new ArrayList<Throwable>();
        for (int index = replaced.size() - 1; index >= 0; index--) {
            var file = replaced.get(index);
            try {
                if (file.existed()) {
                    loader.replaceFile(file.previous(), file.target());
                } else {
                    Files.deleteIfExists(file.target());
                }
            } catch (RuntimeException | IOException failure) {
                failures.add(failure);
            }
        }
        if (!failures.isEmpty()) {
            var rollback = new FibraConfigException(FibraConfigErrorStage.ROLLBACK,
                "cannot restore config files after commit failure", loader.configPath(),
                null, null, original);
            failures.forEach(rollback::addSuppressed);
            throw rollback;
        }
    }

    private void cleanupWorkspace() {
        try {
            deleteTree(workspace);
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot clean config workspace " + workspace,
                exception);
        }
    }

    private void requireUsable() {
        if (state == State.COMPLETED || state == State.ROLLED_BACK) {
            throw new IllegalStateException("config change is closed");
        }
    }

    private void requireState(State expected, String operation) {
        if (state != expected) {
            throw new IllegalStateException(operation + " requires " + expected
                + " config change state, actual=" + state);
        }
    }

    private static List<PreparedFile> prepareFiles(FibraConfigLoader loader,
                                                   FibraConfigSnapshot resolved,
                                                   Path stagedConfig, Path stagedBase,
                                                   Path workspace) {
        var sources = new LinkedHashSet<Path>();
        sources.add(resolved.rootPath());
        resolved.allEntries().forEach(entry -> sources.add(entry.source()));
        var files = new ArrayList<PreparedFile>();
        try {
            for (var source : sources.stream().sorted().toList()) {
                var target = map(source, stagedConfig, stagedBase, loader.configPath(),
                    loader.configPath().getParent());
                var relative = loader.configPath().getParent().relativize(target);
                var next = workspace.resolve("next").resolve(relative);
                Files.createDirectories(next.getParent());
                copyDurably(source, next);
                var existed = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS);
                Path previous = null;
                if (existed) {
                    previous = workspace.resolve("previous").resolve(relative);
                    Files.createDirectories(previous.getParent());
                    copyDurably(target, previous);
                }
                files.add(new PreparedFile(target, next, previous, existed));
            }
            return List.copyOf(files);
        } catch (IOException exception) {
            throw new FibraConfigException(FibraConfigErrorStage.WRITE,
                "cannot prepare replacement config files", loader.configPath(), null,
                null, exception);
        }
    }

    private static FibraConfigSnapshot remap(FibraConfigSnapshot snapshot,
                                              Path stagedConfig, Path stagedBase,
                                              Path targetConfig, Path targetBase) {
        var entries = snapshot.entries().stream()
            .map(entry -> remap(entry, stagedConfig, stagedBase, targetConfig, targetBase))
            .toList();
        return new FibraConfigSnapshot(targetConfig, entries);
    }

    private static FibraConfigEntry remap(FibraConfigEntry entry, Path stagedConfig,
                                          Path stagedBase, Path targetConfig,
                                          Path targetBase) {
        var builder = new FibraConfigEntry.Builder()
            .id(entry.id()).entryId(entry.entryId())
            .sourceEntryId(entry.sourceEntryId()).kind(entry.kind())
            .pluginId(entry.pluginId())
            .source(map(entry.source(), stagedConfig, stagedBase, targetConfig, targetBase))
            .declaredDisabled(entry.declaredDisabled()).disabled(entry.disabled())
            .config(entry.config()).inject(entry.inject())
            .localIntercept(entry.localIntercept()).localIsolate(entry.localIsolate())
            .intercept(entry.intercept()).isolate(entry.isolate())
            .children(entry.children().stream().map(child -> remap(child, stagedConfig,
                stagedBase, targetConfig, targetBase)).toList());
        if (entry.includedPath() != null) {
            builder.includedPath(map(entry.includedPath(), stagedConfig, stagedBase,
                targetConfig, targetBase));
        }
        return builder.build();
    }

    private static Path map(Path source, Path stagedConfig, Path stagedBase,
                            Path targetConfig, Path targetBase) {
        var normalized = source.toAbsolutePath().normalize();
        if (normalized.equals(stagedConfig.toAbsolutePath().normalize())) {
            return targetConfig;
        }
        if (!normalized.startsWith(stagedBase)) {
            throw new FibraConfigException(FibraConfigErrorStage.VALIDATE,
                "replacement config include leaves staged config tree", normalized,
                null, null, null);
        }
        var target = targetBase.resolve(stagedBase.relativize(normalized)).normalize();
        if (!target.startsWith(targetBase)) {
            throw new FibraConfigException(FibraConfigErrorStage.VALIDATE,
                "replacement config target leaves host config tree", target, null,
                null, null);
        }
        return target;
    }

    private static Path validateWorkspace(Path workspace) {
        var normalized = Objects.requireNonNull(workspace, "workspace")
            .toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("workspace must be an existing directory: "
                + normalized);
        }
        try (var children = Files.list(normalized)) {
            if (children.findAny().isPresent()) {
                throw new IllegalArgumentException("workspace must be empty: " + normalized);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot inspect workspace " + normalized,
                exception);
        }
        return normalized;
    }

    private static void validateStagedTree(Path root) {
        try (var paths = Files.walk(root)) {
            var symlink = paths.filter(Files::isSymbolicLink).findFirst();
            if (symlink.isPresent()) {
                throw new FibraConfigException(FibraConfigErrorStage.VALIDATE,
                    "replacement config tree must not contain symbolic links",
                    symlink.orElseThrow(), null, null, null);
            }
        } catch (IOException exception) {
            throw new FibraConfigException(FibraConfigErrorStage.READ,
                "cannot inspect replacement config tree", root, null, null, exception);
        }
    }

    private static void copyDurably(Path source, Path target) throws IOException {
        var bytes = Files.readAllBytes(source);
        try (var channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void cleanup(Path workspace, RuntimeException original) {
        try {
            deleteTree(workspace);
        } catch (IOException exception) {
            original.addSuppressed(exception);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record PreparedFile(Path target, Path next, Path previous, boolean existed) { }

    private enum State {
        PREPARED,
        COMMITTING,
        COMMITTED,
        COMPLETED,
        ROLLED_BACK
    }
}
