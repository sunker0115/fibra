package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.DrainingDisposable;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.engine.deployment.FileDeploymentTargetStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliHostOwnershipTest {
    @TempDir Path work;

    @Test
    void successfulCloseAllowsTheProfileToReopen() throws Exception {
        runIsolated("success");
    }

    @Test
    void ordinaryStartupFailureReleasesStoresAndAllowsTheProfileToReopen()
        throws Exception {
        runIsolated("startup-failure");
    }

    @Test
    void failureBeforeEngineTransferReleasesTheAlreadyOpenedPackageStore()
        throws Exception {
        runIsolated("pre-transfer-failure");
    }

    @Test
    void failedDrainDuringOpenCleanupRetainsBothStoreOwners() throws Exception {
        runIsolated("drain-failure");
    }

    private void runIsolated(String scenario) throws Exception {
        // A failed drain intentionally retains resources until host termination.
        // Keep that ownership and any runtime threads outside the Surefire JVM.
        var output = work.resolve("process.log");
        var process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path")),
            OwnershipProcess.class.getName(), scenario, work.toString())
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS),
                () -> "ownership scenario timed out: " + scenario);
            var assertion = work.resolve("assertion.txt");
            assertEquals(0, process.exitValue(),
                () -> scenario + " failed:\n" + read(assertion) + read(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS),
                    "isolated ownership process did not terminate");
            }
        }
    }

    private static String read(Path file) {
        try { return Files.exists(file) ? Files.readString(file) : ""; }
        catch (java.io.IOException failure) { return failure.toString(); }
    }

    public static final class OwnershipProcess {
        private static final AtomicBoolean FAIL_START = new AtomicBoolean();
        private static final AtomicBoolean FAIL_DRAIN = new AtomicBoolean();
        private static final AtomicInteger STARTS = new AtomicInteger();
        private static final AtomicInteger DRAINS = new AtomicInteger();
        private static final AtomicInteger RELEASES = new AtomicInteger();

        public static void main(String[] args) throws Exception {
            var work = Path.of(args[1]);
            int status = 1;
            try {
                run(args[0], work);
                status = 0;
            } catch (Throwable failure) {
                var trace = new StringWriter();
                failure.printStackTrace(new PrintWriter(trace));
                Files.writeString(work.resolve("assertion.txt"), trace.toString());
            } finally {
                // This is the Host termination boundary, including retained locks.
                System.exit(status);
            }
        }

        private static void run(String scenario, Path work) throws Exception {
            FAIL_START.set(!"success".equals(scenario));
            FAIL_DRAIN.set("drain-failure".equals(scenario));
            var paths = CliPaths.resolve(work.resolve("home"), "default",
                null, null, null, null);
            if ("pre-transfer-failure".equals(scenario)) {
                Files.createDirectories(paths.profileData());
                Files.writeString(paths.stateRoot(), "target store cannot open a file as its directory");
                var failure = assertThrows(RuntimeException.class,
                    () -> CliHost.open(paths));
                assertTrue(failure.getMessage().contains("cannot open deployment target store"));
                assertEquals(0, STARTS.get());
                try (var packages = new PluginPackageStore(paths.packageStoreRoot())) {
                    assertTrue(Files.isDirectory(paths.packageStoreRoot()));
                }
                return;
            }
            javaPackage(paths.pluginsRoot().resolve("ownership"));
            Files.createDirectories(paths.profileFile().getParent());
            Files.writeString(paths.profileFile(),
                desired("a-owner", "owner") + desired("b-start", "start"));
            Files.writeString(paths.profilePackagesFile(), "- ownership\n");

            if ("success".equals(scenario)) {
                try (var host = CliHost.open(paths)) {
                    assertEquals(1, STARTS.get());
                    assertStoresOwned(paths);
                }
                assertEquals(1, RELEASES.get());
                assertProfileReopens(paths);
                return;
            }

            var failure = assertThrows(RuntimeException.class,
                () -> CliHost.open(paths));
            assertEquals("EngineChangeException", failure.getClass().getSimpleName(),
                "the failure must occur after the real Java units were promoted");
            assertEquals(1, STARTS.get(), "entry A must activate before entry B fails");
            assertTrue(DRAINS.get() > 0, "open cleanup must reach entry A's drain");

            if (FAIL_DRAIN.get()) {
                assertEquals(0, RELEASES.get(), "failed drain must retain its resource");
                assertTrue(failure.getSuppressed().length > 0,
                    "open must retain the engine shutdown failure on its primary error");
                assertStoresOwned(paths);
            } else {
                assertEquals(1, RELEASES.get());
                FAIL_START.set(false);
                assertProfileReopens(paths);
            }
        }

        private static void assertStoresOwned(CliPaths paths) {
            assertAll("live runtime ownership must exclude a second store owner",
                () -> assertThrows(RuntimeException.class, () -> {
                    try (var ignored = new PluginPackageStore(paths.packageStoreRoot())) {
                        // Close an unexpected second owner before failing the assertion.
                    }
                }, "package store was released while a runtime resource is still retained"),
                () -> assertThrows(RuntimeException.class, () -> {
                    try (var ignored = new FileDeploymentTargetStore(paths.stateRoot())) {
                        // Check target ownership independently of package-store rejection.
                    }
                }, "target store was released while a runtime resource is still retained"));
        }

        private static void assertProfileReopens(CliPaths paths) {
            try (var packages = new PluginPackageStore(paths.packageStoreRoot());
                 var targets = new FileDeploymentTargetStore(paths.stateRoot())) {
                assertTrue(targets.load().isPresent());
            }
            try (var reopened = CliHost.open(paths)) {
                assertTrue(reopened.registry().snapshot().target().isPresent());
                assertStoresOwned(paths);
            }
            assertEquals(2, RELEASES.get());
        }
    }

    private static String desired(String id, String config) {
        return "- id: " + id + "\n"
            + "  plugin: {id: ownership, facet: main, definition: ownership}\n"
            + "  config: " + config + "\n";
    }

    private static void javaPackage(Path root) throws Exception {
        Files.createDirectories(root);
        try (var output = new JarOutputStream(Files.newOutputStream(root.resolve("plugin.jar")))) {
            output.putNextEntry(new JarEntry("META-INF/fibra/plugin.yaml"));
            output.write(("entrypoint: " + OwnershipEntrypoint.class.getName() + '\n')
                .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            var className = OwnershipEntrypoint.class.getName().replace('.', '/') + ".class";
            output.putNextEntry(new JarEntry(className));
            try (var input = OwnershipEntrypoint.class.getResourceAsStream('/' + className)) {
                output.write(Objects.requireNonNull(input, className).readAllBytes());
            }
            output.closeEntry();
        }
        Files.writeString(root.resolve("fibra-package.yaml"),
            "format: 1\nid: ownership\nversion: 1.0.0\nfacets:\n"
                + "  - id: main\n    role: host\n    runtime: java\n"
                + "    target: host\n    payload: plugin.jar\n"
                + "    dependencies: []\n    capabilities: []\n");
    }

    public static final class OwnershipEntrypoint implements PluginEntrypoint<String> {
        @Override public PluginDefinition<String> definition() {
            return PluginDefinition.builder("ownership", String.class,
                () -> (context, config) -> {
                    if ("owner".equals(config)) {
                        context.effects().add(new DrainingDisposable() {
                            @Override public Mono<Void> drain() {
                                return Mono.defer(() -> {
                                    OwnershipProcess.DRAINS.incrementAndGet();
                                    return OwnershipProcess.FAIL_DRAIN.get()
                                        ? Mono.error(new IllegalStateException("ownership fixture drain failed"))
                                        : Mono.empty();
                                });
                            }
                            @Override public Mono<Void> dispose() {
                                return Mono.fromRunnable(OwnershipProcess.RELEASES::incrementAndGet);
                            }
                        });
                        OwnershipProcess.STARTS.incrementAndGet();
                    } else if (OwnershipProcess.FAIL_START.get()) {
                        assertEquals(1, OwnershipProcess.STARTS.get(),
                            "fixture requires entry A to start before entry B");
                        return Mono.error(new IllegalStateException("ownership fixture start failed"));
                    }
                    return Mono.empty();
                }).build();
        }
    }
}
