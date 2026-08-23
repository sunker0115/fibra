package com.sstlfsj.fibra.loader.config;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.FibraState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoaderBusyException;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import example.fibra.config.ConfigLoaderEntrypoint;
import example.fibra.config.ConfigConsumerEntrypoint;
import example.fibra.config.ConfigProviderEntrypoint;
import example.fibra.config.ConfigValue;
import example.fibra.config.TypedConfigEntrypoint;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.awaitility.Awaitility.await;

class FibraConfigLoaderTest {
    private static final ServiceKey<String> VALUE =
        ServiceKey.of("fixture.value", String.class);

    @Test
    void loadsTwoInstancesUpdatesInPlaceAndRollsBackFailedConfig(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var config = work.resolve("fibra.yaml");
        writeConfig(config, "one", "two");

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            var first = loader.load();
            var firstRuntime = loader.resolve("first").orElseThrow();
            var secondRuntime = loader.resolve("second").orElseThrow();
            var firstFibra = firstRuntime.fibra();
            var secondFibra = secondRuntime.fibra();

            assertEquals("first:one", firstRuntime.context().get(VALUE));
            assertEquals("second:two", secondRuntime.context().get(VALUE));

            writeConfig(config, "updated", "two");
            var updated = loader.refresh();

            assertNotSame(first, updated);
            assertSame(firstFibra, loader.resolve("first").orElseThrow().fibra());
            assertSame(secondFibra, loader.resolve("second").orElseThrow().fibra());
            assertEquals("first:updated", loader.resolve("first").orElseThrow()
                .context().get(VALUE));

            writeConfig(config, "changed-before-failure", "fail");
            var error = assertThrows(FibraConfigException.class, loader::refresh);

            assertEquals(FibraConfigErrorStage.APPLY, error.stage());
            assertEquals("second", error.entryId());
            assertSame(updated, loader.snapshot());
            assertSame(firstFibra, loader.resolve("first").orElseThrow().fibra());
            assertEquals("first:updated", loader.resolve("first").orElseThrow()
                .context().get(VALUE));
            assertEquals("second:two", loader.resolve("second").orElseThrow()
                .context().get(VALUE));

            writeConfig(config, "updated", "two");
            assertSame(updated, loader.refresh());
        }
    }

    @Test
    void failedMountAfterAReplacementRestoresTheWholePreviousRuntime(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var config = work.resolve("fibra.yaml");
        Files.writeString(config, """
            - id: first
              name: fixture
              isolate:
                fixture.value: old
              config: one
            """);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            var current = loader.load();

            Files.writeString(config, """
                - id: first
                  name: fixture
                  isolate:
                    fixture.value: replacement
                  config: changed
                - id: second
                  name: fixture
                  config: fail
                """);

            assertEquals(FibraConfigErrorStage.APPLY,
                assertThrows(FibraConfigException.class, loader::refresh).stage());
            assertSame(current, loader.snapshot());
            assertEquals("first:one",
                loader.resolve("first").orElseThrow().context().get(VALUE));
            assertTrue(loader.resolve("second").isEmpty());
        }
    }

    @Test
    void rollbackFailureIsSuppressedDirectlyOnTheRollbackException(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var config = work.resolve("fibra.yaml");
        writeConfig(config, "rollback-fail", "two");

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            var current = loader.load();
            writeConfig(config, "fail", "two");

            var error = assertThrows(FibraConfigException.class, loader::refresh);

            assertEquals(FibraConfigErrorStage.ROLLBACK, error.stage());
            assertEquals(1, error.getSuppressed().length);
            assertEquals(FibraConfigErrorStage.APPLY,
                ((FibraConfigException) error.getCause()).stage());
            assertSame(current, loader.snapshot());
        }
    }

    @Test
    void nameOnlyInjectActivatesConsumerWhenLaterProviderArrives(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("consumer.jar"), "consumer",
            ConfigConsumerEntrypoint.class);
        writePluginJar(plugins.resolve("provider.jar"), "provider",
            ConfigProviderEntrypoint.class);
        var config = work.resolve("fibra.yaml");
        Files.writeString(config, """
            - id: consumer-entry
              name: consumer
              inject: [source.value]
            - id: provider-entry
              name: provider
            """);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            loader.load();

            var consumer = loader.resolve("consumer-entry").orElseThrow();
            assertEquals(FibraState.ACTIVE, consumer.fibra().state());
            assertEquals("ready:consumer-entry", consumer.context().get(
                ServiceKey.of("consumer.result", String.class)));
        }
    }

    @Test
    void isolateBoundaryChangeReplacesOnlyTheChangedEntry(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var config = work.resolve("fibra.yaml");
        writeConfig(config, "one", "two");

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            loader.load();
            var firstUid = loader.resolve("first").orElseThrow().fibra().uid();
            var secondFibra = loader.resolve("second").orElseThrow().fibra();

            Files.writeString(config, """
                - id: first
                  name: fixture
                  isolate:
                    fixture.value: changed-scope
                  config: one
                - id: second
                  name: fixture
                  isolate:
                    fixture.value: second
                  config: two
                """);
            loader.refresh();

            assertTrue(firstUid.longValue()
                != loader.resolve("first").orElseThrow().fibra().uid().longValue());
            assertSame(secondFibra, loader.resolve("second").orElseThrow().fibra());
        }
    }

    @Test
    void disabledGroupKeepsItsScopeAndMountsDescendantsWhenEnabled(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var config = work.resolve("fibra.yaml");
        writeDisabledGroup(config, true);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            loader.load();
            var groupFibra = loader.resolve("group").orElseThrow().fibra();

            assertTrue(loader.resolve("group:child").isEmpty());
            writeDisabledGroup(config, false);
            loader.refresh();

            assertSame(groupFibra, loader.resolve("group").orElseThrow().fibra());
            assertEquals("group:child:enabled",
                loader.resolve("group:child").orElseThrow().context().get(VALUE));
        }
    }

    @Test
    void watcherKeepsLastGoodRuntimeAndRecoversAfterARefreshFailure(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var config = work.resolve("fibra.yaml");
        writeConfig(config, "one", "two");
        var failures = new CopyOnWriteArrayList<FibraConfigReloadFailure>();

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            loader.load();
            try (var watcher = loader.watch(Duration.ofMillis(30), failures::add)) {
                writeConfig(config, "watched", "two");
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertEquals("first:watched", loader.resolve("first").orElseThrow()
                        .context().get(VALUE)));

                Files.writeString(config, "not: [valid");
                await().atMost(Duration.ofSeconds(5)).until(() -> !failures.isEmpty());
                assertEquals("first:watched", loader.resolve("first").orElseThrow()
                    .context().get(VALUE));
                assertEquals(FibraConfigErrorStage.PARSE,
                    failures.getLast().exception().stage());

                writeConfig(config, "recovered", "two");
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertEquals("first:recovered", loader.resolve("first").orElseThrow()
                        .context().get(VALUE)));

                var failureCount = failures.size();
                Files.delete(config);
                await().atMost(Duration.ofSeconds(3)).until(() ->
                    failures.size() > failureCount);
                assertEquals(FibraConfigErrorStage.READ,
                    failures.getLast().exception().stage());
                assertEquals("first:recovered", loader.resolve("first").orElseThrow()
                    .context().get(VALUE));

                writeConfig(config, "recreated", "two");
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertEquals("first:recreated", loader.resolve("first").orElseThrow()
                        .context().get(VALUE)));
            }
        }
    }

    @RepeatedTest(5)
    void watcherRecoversWhenAMissingIncludedFileIsCreated(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var config = work.resolve("fibra.yaml");
        var includedDirectory = work.resolve("late");
        var included = includedDirectory.resolve("entries.yaml");
        writeConfig(config, "one", "two");
        var failures = new CopyOnWriteArrayList<FibraConfigReloadFailure>();

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            loader.load();
            try (var watcher = loader.watch(Duration.ofMillis(30), failures::add)) {
                Files.writeString(config, """
                    - id: late
                      include: ./late/entries.yaml
                    """);
                await().atMost(Duration.ofSeconds(5)).until(() -> !failures.isEmpty());
                assertEquals(included.toFile().getCanonicalFile().toPath(),
                    failures.getLast().exception().path());
                assertTrue(loader.watchedPaths().contains(
                    included.toFile().getCanonicalFile().toPath()));

                Files.createDirectory(includedDirectory);
                Files.writeString(included, """
                    - id: child
                      name: fixture
                      config: recovered
                    """);

                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    var child = loader.resolve("late:child");
                    assertTrue(child.isPresent());
                    assertEquals("late:child:recovered", child.orElseThrow()
                        .context().get(VALUE));
                });
            }
        }
    }

    @Test
    void loaderCloseWaitsForWatcherAndRejectsAConcurrentWatcher(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var config = work.resolve("fibra.yaml");
        writeConfig(config, "one", "two");
        var callbackEntered = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        var closeFinished = new CountDownLatch(1);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            loader.load();
            loader.watch(Duration.ZERO, ignored -> {
                callbackEntered.countDown();
                try {
                    releaseCallback.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            });
            Files.writeString(config, "not: [valid");
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
            var closer = Thread.ofPlatform().start(() -> {
                loader.close();
                closeFinished.countDown();
            });

            try {
                assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS));
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    var error = assertThrows(IllegalStateException.class,
                        () -> loader.watch(Duration.ZERO, ignored -> { }));
                    assertTrue(error.getMessage().contains("closed"));
                });
            } finally {
                releaseCallback.countDown();
            }
            assertTrue(closeFinished.await(5, TimeUnit.SECONDS));
            closer.join();
        }
    }

    @Test
    void failureCallbackCanCloseLoaderWhileAnotherThreadIsClosing(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var config = work.resolve("fibra.yaml");
        writeConfig(config, "one", "two");
        var callbackEntered = new CountDownLatch(1);
        var closeFromCallback = new CountDownLatch(1);
        var callbackCloseReturned = new CountDownLatch(1);
        var externalCloseReturned = new CountDownLatch(1);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins)) {
            var loader = FibraConfigLoader.builder(root, artifacts, config).build();
            artifacts.loadArtifacts();
            loader.load();
            loader.watch(Duration.ZERO, ignored -> {
                callbackEntered.countDown();
                try {
                    closeFromCallback.await();
                    loader.close();
                    callbackCloseReturned.countDown();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            });
            Files.writeString(config, "not: [valid");
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
            var externalCloser = Thread.ofVirtual().start(() -> {
                loader.close();
                externalCloseReturned.countDown();
            });

            try {
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    var error = assertThrows(IllegalStateException.class,
                        () -> loader.watch(Duration.ZERO, ignored -> { }));
                    assertTrue(error.getMessage().contains("closed"));
                });
                closeFromCallback.countDown();
                assertTrue(callbackCloseReturned.await(5, TimeUnit.SECONDS));
                assertTrue(externalCloseReturned.await(5, TimeUnit.SECONDS));
                externalCloser.join();
            } finally {
                closeFromCallback.countDown();
            }
        }
    }

    @Test
    void refreshRemountsAnEntryRemovedThroughTheArtifactApi(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var config = work.resolve("fibra.yaml");
        writeConfig(config, "one", "two");

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            var current = loader.load();

            artifacts.unmount("first");
            assertTrue(loader.resolve("first").isEmpty());

            assertSame(current, loader.refresh());
            assertEquals("first:one",
                loader.resolve("first").orElseThrow().context().get(VALUE));
        }
    }

    @Test
    void refreshFailsBusyWithoutChangingRuntimeDuringAnArtifactTransaction(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var config = work.resolve("fibra.yaml");
        writeConfig(config, "one", "two");

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            loader.load();
            var firstContext = loader.resolve("first").orElseThrow().context();
            writeConfig(config, "serialized", "two");
            var lockEntered = new CountDownLatch(1);
            var releaseLock = new CountDownLatch(1);
            var holder = Thread.ofPlatform().start(() -> artifacts.runExclusive(() -> {
                lockEntered.countDown();
                try {
                    if (!releaseLock.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("exclusive test lock was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertTrue(lockEntered.await(3, TimeUnit.SECONDS));

            try {
                assertThrows(FibraPluginLoaderBusyException.class, loader::refresh);
                assertEquals("first:one", firstContext.get(VALUE));
                assertEquals(List.of("first", "second"), artifacts.entryIds());
            } finally {
                releaseLock.countDown();
            }
            holder.join();

            loader.refresh();
            assertEquals("first:serialized", loader.resolve("first").orElseThrow()
                .context().get(VALUE));
        }
    }

    @Test
    void typedConfigRefreshAndArtifactReloadRejectOverlapAndConvergeAfterRetry(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        var incoming = Files.createDirectory(work.resolve("incoming"));
        writePluginJar(plugins.resolve("typed.jar"), "typed", "1.0.0",
            TypedConfigEntrypoint.class, ConfigValue.class);
        var candidate = incoming.resolve("typed-2.0.0.jar");
        writePluginJar(candidate, "typed", "2.0.0", TypedConfigEntrypoint.class,
            ConfigValue.class);
        var config = work.resolve("fibra.yaml");
        Files.writeString(config, """
            - id: typed-entry
              name: typed
              config:
                value: before
            """);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            loader.load();
            var oldClassLoader = artifacts.configType("typed").getClassLoader();
            Files.writeString(config, """
                - id: typed-entry
                  name: typed
                  config:
                    value: after
                """);
            var applyFinished = new CountDownLatch(1);
            var releaseApply = new CountDownLatch(1);
            var reloadFailure = new AtomicReference<Throwable>();
            var reload = Thread.ofPlatform().start(() -> {
                try {
                    artifacts.runExclusive(() -> {
                        artifacts.reloadArtifact(candidate);
                        applyFinished.countDown();
                        try {
                            if (!releaseApply.await(3, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("apply test gate was not released");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                    });
                } catch (Throwable failure) {
                    reloadFailure.set(failure);
                }
            });

            assertTrue(applyFinished.await(3, TimeUnit.SECONDS));
            assertThrows(FibraPluginLoaderBusyException.class, loader::refresh);
            releaseApply.countDown();
            reload.join();

            var reloadError = reloadFailure.get();
            if (reloadError != null) {
                throw new AssertionError("artifact reload failed", reloadError);
            }
            loader.refresh();
            assertNotSame(oldClassLoader, artifacts.configType("typed").getClassLoader());
            assertEquals("typed-entry:after",
                loader.resolve("typed-entry").orElseThrow().context().get(VALUE));
        }
    }

    @Test
    void convertsConfigToATypeOwnedByThePluginClassLoader(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("typed.jar"), "typed", TypedConfigEntrypoint.class,
            ConfigValue.class);
        var config = work.resolve("fibra.yaml");
        Files.writeString(config, """
            - id: typed-entry
              name: typed
              config:
                value: converted
            """);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            loader.load();

            assertEquals("typed-entry:converted",
                loader.resolve("typed-entry").orElseThrow().context().get(VALUE));
            assertNotSame(ConfigValue.class.getClassLoader(),
                artifacts.configType("typed").getClassLoader());
        }
    }

    @Test
    void artifactReloadRebuildsTypedConfigAndRefreshesResolvedRuntime(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        var incoming = Files.createDirectory(work.resolve("incoming"));
        writePluginJar(plugins.resolve("typed.jar"), "typed", "1.0.0",
            TypedConfigEntrypoint.class, ConfigValue.class);
        var candidate = incoming.resolve("typed-2.0.0.jar");
        writePluginJar(candidate, "typed", "2.0.0", TypedConfigEntrypoint.class,
            ConfigValue.class);
        var config = work.resolve("fibra.yaml");
        Files.writeString(config, """
            - id: typed-entry
              name: typed
              config:
                value: converted
            """);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            loader.load();
            var before = loader.resolve("typed-entry").orElseThrow().fibra();
            var oldClassLoader = artifacts.configType("typed").getClassLoader();

            artifacts.reloadArtifact(candidate);

            var after = loader.resolve("typed-entry").orElseThrow();
            assertNotSame(before, after.fibra());
            assertEquals(FibraState.DISPOSED, before.state());
            assertSame(after.fibra(), artifacts.fibra("typed-entry").orElseThrow());
            assertEquals("typed-entry:converted", after.context().get(VALUE));
            assertNotSame(oldClassLoader, artifacts.configType("typed").getClassLoader());
            assertSame(loader.snapshot(), loader.refresh());
        }
    }

    @Test
    void distinguishesArtifactResolutionFromTypedConfigConversion(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("typed.jar"), "typed", TypedConfigEntrypoint.class,
            ConfigValue.class);
        var config = work.resolve("fibra.yaml");
        Files.writeString(config, """
            - id: unresolved
              name: missing-artifact
            """);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            var resolveError = assertThrows(FibraConfigException.class, loader::load);
            assertEquals(FibraConfigErrorStage.RESOLVE, resolveError.stage());
            assertEquals("unresolved", resolveError.entryId());
            assertEquals("missing-artifact", resolveError.pluginId());
        }

        Files.writeString(config, """
            - id: invalid-config
              name: typed
              config: [not, an, object]
            """);
        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            var convertError = assertThrows(FibraConfigException.class, loader::load);
            assertEquals(FibraConfigErrorStage.CONVERT, convertError.stage());
            assertEquals("invalid-config", convertError.entryId());
            assertEquals("typed", convertError.pluginId());
        }
    }

    @Test
    void disabledEntriesStillValidateArtifactAndTypedConfig(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("typed.jar"), "typed", TypedConfigEntrypoint.class,
            ConfigValue.class);
        var config = work.resolve("fibra.yaml");
        Files.writeString(config, """
            - id: disabled-missing
              name: missing
              disabled: true
            """);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            assertEquals(FibraConfigErrorStage.RESOLVE,
                assertThrows(FibraConfigException.class, loader::load).stage());
        }

        Files.writeString(config, """
            - id: disabled-invalid
              name: typed
              disabled: true
              config: [not, an, object]
            """);
        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            assertEquals(FibraConfigErrorStage.CONVERT,
                assertThrows(FibraConfigException.class, loader::load).stage());
        }
    }

    @Test
    void programmaticMutationsCommitRuntimeAndFileTogether(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var config = work.resolve("fibra.json");
        Files.writeString(config, """
            [{"id":"first","name":"fixture","isolate":{"fixture.value":"first"},"config":"one"},
             {"id":"second","name":"fixture","isolate":{"fixture.value":"second"},"config":"two"}]
            """);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            loader.load();

            assertEquals("third", loader.create(null, 1,
                java.util.Map.of("id", "third", "name", "fixture", "config", "three",
                    "isolate", java.util.Map.of("fixture.value", "third"))));
            assertEquals("third:three", loader.resolve("third").orElseThrow()
                .context().get(VALUE));
            loader.update("first", FibraConfigPatch.override("first", "fixture",
                java.util.Map.of("config", "updated")), null, 0);
            assertEquals("first:updated", loader.resolve("first").orElseThrow()
                .context().get(VALUE));
            loader.remove("second");

            var persisted = new ConfigDocumentReader(ConfigLimits.defaults()).read(config);
            assertEquals(List.of("first", "third"),
                persisted.stream().map(entry -> entry.get("id")).toList());
        }
    }

    @Test
    void failedProgrammaticUpdateLeavesOriginalBytesAndSnapshotUntouched(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var config = work.resolve("fibra.yaml");
        writeConfig(config, "one", "two");

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            var current = loader.load();
            var original = Files.readAllBytes(config);

            var error = assertThrows(FibraConfigException.class, () ->
                loader.update("first", FibraConfigPatch.override("first", "fixture",
                    java.util.Map.of("config", "fail")), null, 0));

            assertEquals(FibraConfigErrorStage.APPLY, error.stage());
            assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(config)));
            assertSame(current, loader.snapshot());
            assertEquals("first:one", loader.resolve("first").orElseThrow()
                .context().get(VALUE));
        }
    }

    @Test
    void programmaticMutationCannotWriteAValueThatTheConfiguredLimitsReject(
        @TempDir Path work) throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var config = work.resolve("fibra.yaml");
        writeConfig(config, "one", "two");

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .maxStringLength(32)
                 .build()) {
            artifacts.loadArtifacts();
            var current = loader.load();
            var original = Files.readAllBytes(config);

            var error = assertThrows(FibraConfigException.class, () -> loader.create(null, -1,
                java.util.Map.of("id", "third", "name", "fixture", "config",
                    "x".repeat(64))));

            assertEquals(FibraConfigErrorStage.WRITE, error.stage());
            assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(config)));
            assertSame(current, loader.snapshot());
            assertTrue(loader.resolve("third").isEmpty());
        }
    }

    @Test
    void programmaticMutationWritesTheOwningIncludedFile(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var included = work.resolve("included.yaml");
        Files.writeString(included, """
            - id: provider
              name: fixture
              isolate:
                fixture.value: provider
              config: one
            """);
        var config = work.resolve("fibra.yaml");
        Files.writeString(config, """
            - id: bundle
              include: ./included.yaml
            """);
        var rootBytes = Files.readAllBytes(config);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            loader.load();

            loader.update("bundle:provider",
                FibraConfigPatch.override("bundle:provider", "fixture",
                    java.util.Map.of("config", "included-update")),
                "bundle", 0);

            assertTrue(java.util.Arrays.equals(rootBytes, Files.readAllBytes(config)));
            assertEquals("bundle:provider:included-update",
                loader.resolve("bundle:provider").orElseThrow().context().get(VALUE));
            assertEquals("included-update", new ConfigDocumentReader(ConfigLimits.defaults())
                .read(included).getFirst().get("config"));
        }
    }

    @Test
    void programmaticUpdateCanMoveAnEntryAcrossConfigFiles(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var included = work.resolve("included.yaml");
        Files.writeString(included, """
            - id: provider
              name: fixture
              isolate:
                fixture.value: provider
              config: one
            """);
        var config = work.resolve("fibra.yaml");
        Files.writeString(config, """
            - id: bundle
              include: ./included.yaml
            """);

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .build()) {
            artifacts.loadArtifacts();
            loader.load();

            loader.update("bundle:provider",
                FibraConfigPatch.override("bundle:provider", "fixture",
                    java.util.Map.of("config", "moved")),
                null, 0);

            assertTrue(loader.resolve("bundle:provider").isEmpty());
            assertEquals("provider:moved", loader.resolve("provider").orElseThrow()
                .context().get(VALUE));
            assertTrue(new ConfigDocumentReader(ConfigLimits.defaults()).read(included)
                .isEmpty());
            assertEquals(List.of("provider", "bundle"),
                new ConfigDocumentReader(ConfigLimits.defaults()).read(config).stream()
                    .map(entry -> entry.get("id")).toList());
        }
    }

    @Test
    void secondFileReplaceFailureRestoresFilesSnapshotAndRuntime(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var config = work.resolve("a-root.yaml");
        var included = work.resolve("z-included.yaml");
        writeIncludedConfig(config, included, "one");
        var moves = new AtomicInteger();

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .fileMover((source, target) -> {
                     if (moves.incrementAndGet() == 2) {
                         throw new IOException("second replace failed");
                     }
                     Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                         StandardCopyOption.REPLACE_EXISTING);
                 })
                 .build()) {
            artifacts.loadArtifacts();
            var current = loader.load();
            var rootBytes = Files.readAllBytes(config);
            var includedBytes = Files.readAllBytes(included);

            var error = assertThrows(FibraConfigException.class, () -> loader.update(
                "bundle:provider",
                FibraConfigPatch.override("bundle:provider", "fixture",
                    java.util.Map.of("config", "moved")), null, 0));

            assertEquals(FibraConfigErrorStage.WRITE, error.stage());
            assertTrue(java.util.Arrays.equals(rootBytes, Files.readAllBytes(config)));
            assertTrue(java.util.Arrays.equals(includedBytes, Files.readAllBytes(included)));
            assertSame(current, loader.snapshot());
            assertEquals("bundle:provider:one", loader.resolve("bundle:provider")
                .orElseThrow().context().get(VALUE));
            assertTrue(loader.resolve("provider").isEmpty());
        }
    }

    @Test
    void fileAndRuntimeRestoreFailuresAreDirectlySuppressed(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var config = work.resolve("a-root.yaml");
        var included = work.resolve("z-included.yaml");
        writeIncludedConfig(config, included, "restore-fail");
        var moves = new AtomicInteger();

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .fileMover((source, target) -> {
                     var attempt = moves.incrementAndGet();
                     if (attempt == 2 || attempt == 3) {
                         throw new IOException("injected move failure " + attempt);
                     }
                     Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                         StandardCopyOption.REPLACE_EXISTING);
                 })
                 .build()) {
            artifacts.loadArtifacts();
            var current = loader.load();
            var includedBytes = Files.readAllBytes(included);

            var error = assertThrows(FibraConfigException.class, () -> loader.update(
                "bundle:provider",
                FibraConfigPatch.override("bundle:provider", "fixture",
                    java.util.Map.of("config", "moved")), null, 0));

            assertEquals(FibraConfigErrorStage.ROLLBACK, error.stage());
            assertEquals(FibraConfigErrorStage.WRITE,
                ((FibraConfigException) error.getCause()).stage());
            assertEquals(2, error.getSuppressed().length);
            assertTrue(java.util.Arrays.equals(includedBytes, Files.readAllBytes(included)));
            assertSame(current, loader.snapshot());
        }
    }

    @Test
    void programmaticMutationRejectsPatchInsertedEntriesWithStructuredLocation(
        @TempDir Path work) throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var config = work.resolve("fibra.yaml");
        Files.writeString(config, "[]\n");
        var inserted = FibraConfigPatch.insert(java.util.Map.of(
            "id", "synthetic", "name", "fixture", "config", "one",
            "isolate", java.util.Map.of("fixture.value", "synthetic")));

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins);
             FibraConfigLoader loader = FibraConfigLoader.builder(root, artifacts, config)
                 .patches(List.of(inserted))
                 .build()) {
            artifacts.loadArtifacts();
            var current = loader.load();

            var error = assertThrows(FibraConfigException.class, () ->
                loader.remove("synthetic"));

            assertEquals(FibraConfigErrorStage.VALIDATE, error.stage());
            assertEquals(config.toRealPath(), error.path());
            assertEquals("synthetic", error.entryId());
            assertSame(current, loader.snapshot());
            assertTrue(loader.resolve("synthetic").isPresent());
            assertEquals("[]\n", Files.readString(config));
        }
    }

    @Test
    void loadIsSingleUseAndCloseOnlyDisposesManagedEntries(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        writePluginJar(plugins.resolve("fixture.jar"), ConfigLoaderEntrypoint.class);
        var config = work.resolve("fibra.yaml");
        writeConfig(config, "one", "two");

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(root, plugins)) {
            artifacts.loadArtifacts();
            var loader = FibraConfigLoader.builder(root, artifacts, config).build();
            loader.load();

            assertThrows(IllegalStateException.class, loader::load);
            loader.close();

            assertEquals(List.of(), artifacts.entryIds());
            assertEquals(List.of("fixture"), artifacts.artifactIds());
        }
    }

    private static void writeConfig(Path path, String first, String second) throws IOException {
        Files.writeString(path, """
            - id: first
              name: fixture
              isolate:
                fixture.value: first
              config: %s
            - id: second
              name: fixture
              isolate:
                fixture.value: second
              config: %s
            """.formatted(first, second));
    }

    private static void writeIncludedConfig(Path root, Path included, String value)
        throws IOException {
        Files.writeString(included, """
            - id: provider
              name: fixture
              isolate:
                fixture.value: provider
              config: %s
            """.formatted(value));
        Files.writeString(root, """
            - id: bundle
              include: ./%s
            """.formatted(included.getFileName()));
    }

    private static void writeDisabledGroup(Path path, boolean disabled) throws IOException {
        Files.writeString(path, """
            - id: group
              group: true
              disabled: %s
              config:
                - id: child
                  name: fixture
                  isolate:
                    fixture.value: child
                  config: enabled
            """.formatted(disabled));
    }

    private static void writePluginJar(Path path, Class<?> entrypoint) throws IOException {
        writePluginJar(path, "fixture", entrypoint);
    }

    private static void writePluginJar(Path path, String pluginId, Class<?> entrypoint,
                                       Class<?>... supportTypes)
        throws IOException {
        writePluginJar(path, pluginId, "1.0.0", entrypoint, supportTypes);
    }

    private static void writePluginJar(Path path, String pluginId, String version,
                                       Class<?> entrypoint, Class<?>... supportTypes)
        throws IOException {
        var manifest = new Manifest();
        var attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Plugin-Id", pluginId);
        attributes.putValue("Plugin-Version", version);
        attributes.putValue("Plugin-Dependencies", "");
        try (var output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            output.putNextEntry(new JarEntry("META-INF/extensions.idx"));
            output.write((entrypoint.getName() + '\n').getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            addClass(output, entrypoint);
            for (var supportType : supportTypes) {
                addClass(output, supportType);
            }
        }
    }

    private static void addClass(JarOutputStream output, Class<?> type) throws IOException {
        var resource = type.getName().replace('.', '/') + ".class";
        output.putNextEntry(new JarEntry(resource));
        try (InputStream input = type.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing class resource " + resource);
            }
            input.transferTo(output);
        }
        output.closeEntry();
    }

    private static void awaitGate(CountDownLatch latch,
                                  AtomicReference<Throwable> failure) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                failure.set(new IllegalStateException("concurrent start gate timed out"));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failure.set(exception);
        }
    }
}
