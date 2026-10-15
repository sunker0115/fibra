package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ExecutionTarget;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.FacetRole;
import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.PluginFacet;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.FibraException;
import com.sstlfsj.fibra.ManagedPluginControl;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstance;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredEvaluation;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.engine.execution.CompiledRuntimeSlice;
import com.sstlfsj.fibra.engine.deployment.DeploymentTarget;
import com.sstlfsj.fibra.engine.execution.ExecutionUnitKey;
import com.sstlfsj.fibra.engine.deployment.HostCapabilitySnapshot;
import com.sstlfsj.fibra.engine.deployment.PluginFacetSource;
import com.sstlfsj.fibra.engine.deployment.PluginSelection;
import com.sstlfsj.fibra.engine.deployment.ResolvedFacetDependency;
import com.sstlfsj.fibra.engine.runtime.RuntimeTargetSlice;
import com.sstlfsj.fibra.engine.runtime.RuntimeHostServices;
import com.sstlfsj.fibra.engine.runtime.RuntimeUnitDisableRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaRuntimeDriverTest {
    @TempDir Path work;

    @Test
    void fatalSynchronousDescriptorReadPropagatesItsOriginalObjectWithoutCleanup() throws Exception {
        var fatal = new OutOfMemoryError("prebuilt descriptor read fatal");
        var handles = new ArrayList<java.io.Closeable>(); var closes = new AtomicInteger();
        var driver = queryDriver(new JavaFacetDescriptorReader.ResourceAccess() {
            @Override java.util.jar.JarFile open(Path path) throws java.io.IOException {
                var jar = super.open(path); handles.add(jar); return jar;
            }
            @Override java.io.InputStream open(java.util.jar.JarFile jar, java.util.jar.JarEntry entry) throws java.io.IOException {
                var stream = new java.io.FilterInputStream(super.open(jar, entry)) {
                    @Override public byte[] readNBytes(int n) { throw fatal; }
                };
                handles.add(stream); return stream;
            }
            @Override void close(java.io.InputStream stream) throws java.io.IOException { closes.incrementAndGet(); super.close(stream); }
        });
        try {
            Throwable observed;
            try { driver.inspect(facet("fatal-reader", "{}\n", null)).block(java.time.Duration.ofSeconds(2)); observed = null; }
            catch (Throwable failure) { observed = failure; }
            org.junit.jupiter.api.Assertions.assertSame(fatal, observed);
            assertEquals(0, closes.get(), "fatal read must not enter ordinary cleanup");
            assertEquals(1, descriptorReadCount(driver));
            assertThrows(IllegalStateException.class, () -> driver.closeAsync().block());
        } finally { for (var handle : handles.reversed()) handle.close(); }
    }

    @Test
    void fatalSynchronousDescriptorCloseCachesItsOriginalObjectWithoutRetry() throws Exception {
        var fatal = new ThreadDeath();
        var handles = new ArrayList<java.io.Closeable>(); var closes = new AtomicInteger();
        var driver = queryDriver(new JavaFacetDescriptorReader.ResourceAccess() {
            @Override java.util.jar.JarFile open(Path path) throws java.io.IOException {
                var jar = super.open(path); handles.add(jar); return jar;
            }
            @Override java.io.InputStream open(java.util.jar.JarFile jar, java.util.jar.JarEntry entry) throws java.io.IOException {
                var stream = super.open(jar, entry); handles.add(stream); return stream;
            }
            @Override void close(java.io.InputStream stream) { closes.incrementAndGet(); throw fatal; }
        });
        try {
            Throwable observed;
            try { driver.inspect(facet("fatal-closer", "{}\n", null)).block(); observed = null; }
            catch (Throwable failure) { observed = failure; }
            org.junit.jupiter.api.Assertions.assertSame(fatal, observed);
            var owner = indexedOwners(driver).iterator().next();
            org.junit.jupiter.api.Assertions.assertSame(fatal, assertThrows(Error.class, () -> closeDescriptorOwner(owner)));
            org.junit.jupiter.api.Assertions.assertSame(fatal, assertThrows(Error.class, () -> closeDescriptorOwner(owner)));
            assertEquals(1, closes.get()); assertEquals(1, descriptorReadCount(driver));
            assertThrows(IllegalStateException.class, () -> driver.closeAsync().block());
        } finally { for (var handle : handles.reversed()) handle.close(); }
    }

    private static void closeDescriptorOwner(Object owner) throws Throwable {
        var method = owner.getClass().getDeclaredMethod("close"); method.setAccessible(true);
        try { method.invoke(owner); }
        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getTargetException(); }
    }

    @Test
    void synchronousInspectionRemainsUsableOnParallelSubscribers() throws Exception {
        synchronousOperationOnParallel("inspect");
    }

    @Test
    void synchronousProbeRemainsUsableOnParallelSubscribers() throws Exception {
        synchronousOperationOnParallel("probe");
    }

    @Test
    void synchronousPreparationAndCleanupRemainUsableOnParallelSubscribers() throws Exception {
        synchronousOperationOnParallel("prepare");
    }

    private void synchronousOperationOnParallel(String operation) throws Exception {
        var facet = facet("parallel-" + operation, "entrypoint: fixture.SampleEntrypoint\ndefinitionId: sample\n",
            fixture.SampleEntrypoint.class);
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of());
        var source = new PluginFacetSource(facet, List.of());
        if (operation.equals("inspect")) driver.inspect(facet).subscribeOn(reactor.core.scheduler.Schedulers.parallel())
            .block(java.time.Duration.ofSeconds(5));
        else if (operation.equals("probe")) driver.probe(source).subscribeOn(reactor.core.scheduler.Schedulers.parallel())
            .block(java.time.Duration.ofSeconds(5));
        else {
            var candidate = driver.createCandidate(slice(source));
            candidate.prepareAsync().subscribeOn(reactor.core.scheduler.Schedulers.parallel()).block(java.time.Duration.ofSeconds(5));
            assertEquals(1, candidate.preparedPlan().units().size());
            candidate.closeAsync().subscribeOn(reactor.core.scheduler.Schedulers.parallel()).block(java.time.Duration.ofSeconds(5));
        }
        assertEquals(0, descriptorReadCount(driver)); driver.closeAsync().block();
    }

    @Test
    void failedDescriptorCandidateCleanupOnParallelReplaysItsRealFailure() throws Exception {
        var facet = facet("parallel-close-failure", "{}\n", null);
        var handles = new ArrayList<java.io.Closeable>(); var closes = new AtomicInteger();
        var driver = queryDriver(new JavaFacetDescriptorReader.ResourceAccess() {
            @Override java.util.jar.JarFile open(Path path) throws java.io.IOException {
                var jar = super.open(path); handles.add(jar); return jar;
            }
            @Override java.io.InputStream open(java.util.jar.JarFile jar, java.util.jar.JarEntry entry) throws java.io.IOException {
                var stream = super.open(jar, entry); handles.add(stream); return stream;
            }
            @Override void close(java.io.InputStream stream) throws java.io.IOException {
                closes.incrementAndGet(); throw new java.io.IOException("actual descriptor close failed");
            }
        });
        var candidate = driver.createCandidate(slice(new PluginFacetSource(facet, List.of())));
        try {
            var prepared = assertThrows(JavaRuntimeException.class, () -> candidate.prepareAsync()
                .subscribeOn(reactor.core.scheduler.Schedulers.parallel()).block(java.time.Duration.ofSeconds(5)));
            assertEquals(JavaRuntimePhase.CLOSE, prepared.phase());
            var closed = assertThrows(JavaRuntimeException.class, () -> candidate.closeAsync()
                .subscribeOn(reactor.core.scheduler.Schedulers.parallel()).block(java.time.Duration.ofSeconds(5)));
            org.junit.jupiter.api.Assertions.assertSame(prepared, closed);
            org.junit.jupiter.api.Assertions.assertSame(closed, assertThrows(JavaRuntimeException.class,
                () -> candidate.closeAsync().block()));
            assertEquals(1, closes.get()); assertEquals(1, descriptorReadCount(driver));
            assertThrows(IllegalStateException.class, () -> driver.closeAsync().block());
        } finally { for (var handle : handles.reversed()) handle.close(); }
    }

    @Test
    void reusedContractDependencyRejectsAnExecutableDeclarationBeforeParentMetadataOrBinding() throws Exception {
        rejectsReusedDependencyPresenceChange(false);
    }

    @Test
    void reusedExecutableDependencyExplicitlyRejectsAContractDeclarationWithoutChangingItsLease() throws Exception {
        rejectsReusedDependencyPresenceChange(true);
    }

    private void rejectsReusedDependencyPresenceChange(boolean initiallyExecutable) throws Exception {
        var executable = "entrypoint: fixture.SampleEntrypoint\ndefinitionId: sample\n";
        var base = facet("presence-base", initiallyExecutable ? executable : "{}\n",
            initiallyExecutable ? fixture.SampleEntrypoint.class : null, "c".repeat(64), "d".repeat(64));
        var retainedRoot = facet("presence-retained", "entrypoint: fixture.PreparationEntrypoint\ndefinitionId: sample\n",
            fixture.PreparationEntrypoint.class);
        var rejectedRoot = facet("presence-rejected", "entrypoint: fixture.PreparationEntrypoint\ndefinitionId: sample\n",
            fixture.PreparationEntrypoint.class, "e".repeat(64), "f".repeat(64));
        var baseSource = new PluginFacetSource(base, List.of());
        var retainedSource = new PluginFacetSource(retainedRoot, List.of(dependency(base)));
        var rejectedSource = new PluginFacetSource(rejectedRoot, List.of(dependency(base)));
        var metadata = new AtomicInteger(); var closes = new java.util.LinkedHashMap<String, Integer>();
        fixture.PreparationObserver.callback = ignored -> metadata.incrementAndGet();
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of(), getClass().getClassLoader(),
            List.of("java.", "com.sstlfsj.fibra.", "reactor.", "fixture.PreparationObserver"), loader -> {
                closes.merge(loader.getURLs()[0].getPath(), 1, Integer::sum); loader.close();
            });
        var retained = driver.createCandidate(singleEntrySlice(retainedSource, baseSource, retainedSource));
        try {
            retained.prepareAsync().block();
            assertEquals(1, metadata.get());
            var references = wiringReferences(driver);
            assertEquals(Map.of("presence-base", 1, "presence-retained", 1), references);
            // Deliberately replace this SPI fixture with the same key; this is not PackageStore permission.
            var replacement = work.resolve("presence-replacement.jar");
            try (var jar = new JarOutputStream(Files.newOutputStream(replacement))) {
                entry(jar, JavaFacetDescriptorReader.LOCATION, initiallyExecutable ? "{}\n" : executable);
                if (!initiallyExecutable) copyClass(jar, fixture.SampleEntrypoint.class);
            }
            Files.move(replacement, base.facet().payload(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            var rejected = driver.createCandidate(singleEntrySlice(rejectedSource, baseSource, rejectedSource));
            var failure = assertThrows(JavaRuntimeException.class, () -> rejected.prepareAsync().block());
            assertTrue(failure.getMessage().contains("definition presence"), "presence mismatch must be explicit");
            assertEquals(1, metadata.get(), "the dependent parent must not reach metadata or binding");
            assertEquals(references, wiringReferences(driver)); assertTrue(closes.isEmpty());
            rejected.closeAsync().block(); rejected.closeAsync().block();
            assertEquals(references, wiringReferences(driver)); assertTrue(closes.isEmpty());
            retained.closeAsync().block();
            assertEquals(Map.of(base.facet().payload().toString(), 1, retainedRoot.facet().payload().toString(), 1), closes);
            assertEquals(Map.of(), wiringReferences(driver)); driver.closeAsync().block();
        } finally { fixture.PreparationObserver.callback = ignored -> { }; }
    }

    private static Map<String, Integer> wiringReferences(JavaRuntimeDriver driver) throws Exception {
        var poolField = JavaRuntimeDriver.class.getDeclaredField("wiringPool"); poolField.setAccessible(true);
        var values = new java.util.LinkedHashMap<String, Integer>();
        for (var wiring : ((Map<?, ?>) poolField.get(driver)).values()) {
            var facetField = wiring.getClass().getDeclaredField("facet"); facetField.setAccessible(true);
            var referencesField = wiring.getClass().getDeclaredField("references"); referencesField.setAccessible(true);
            values.put(((ManagedFacet) facetField.get(wiring)).artifactId().value(), referencesField.getInt(wiring));
        }
        return Map.copyOf(values);
    }

    @Test
    void reusedWiringStillChecksItsMetadataAgainstTheCurrentDeclaration() throws Exception {
        var facet = facet("reused-name", "entrypoint: fixture.SampleEntrypoint\ndefinitionId: sample\n", fixture.SampleEntrypoint.class);
        var closes = new AtomicInteger();
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of(), getClass().getClassLoader(),
            List.of("java.", "com.sstlfsj.fibra.", "reactor."), loader -> { closes.incrementAndGet(); loader.close(); });
        var source = new PluginFacetSource(facet, List.of());
        var retained = driver.createCandidate(slice(source)); retained.prepareAsync().block();
        // Deliberately replace this SPI fixture with the same key to expose the reuse check.
        // PackageStore's immutable content contract is outside this test.
        var replacement = work.resolve("replacement.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(replacement))) {
            entry(jar, JavaFacetDescriptorReader.LOCATION, "entrypoint: fixture.SampleEntrypoint\ndefinitionId: other-declaration\n");
            copyClass(jar, fixture.SampleEntrypoint.class);
        }
        Files.move(replacement, facet.facet().payload(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var candidate = driver.createCandidate(slice(source));
        assertThrows(JavaRuntimeException.class, () -> candidate.prepareAsync().block());
        candidate.closeAsync().block();
        assertEquals(0, closes.get(), "rejected reuse must retain the original candidate's lease");
        retained.closeAsync().block(); assertEquals(1, closes.get()); driver.closeAsync().block();
    }

    @Test
    void failedDescriptorCleanupKeepsCandidateAndItsDependencyOwner() throws Exception {
        var base = facet("query-base", "{}\n", null, "c".repeat(64), "d".repeat(64));
        var app = facet("query-app", "{}\n", null);
        var appSource = new PluginFacetSource(app, List.of(dependency(base)));
        var handles = new ArrayList<java.io.Closeable>();
        var closes = new AtomicInteger();
        var failedStreams = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<java.io.InputStream, Boolean>());
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of(), getClass().getClassLoader(),
            List.of("java.", "com.sstlfsj.fibra.", "reactor."), loader -> { closes.incrementAndGet(); loader.close(); },
            new JavaFacetDescriptorReader.ResourceAccess() {
                @Override java.util.jar.JarFile open(Path path) throws java.io.IOException {
                    var jar = super.open(path); handles.add(jar); return jar;
                }
                @Override java.io.InputStream open(java.util.jar.JarFile jar, java.util.jar.JarEntry entry) throws java.io.IOException {
                    var stream = super.open(jar, entry); handles.add(stream);
                    if (jar.getName().equals(app.facet().payload().toString())) failedStreams.add(stream);
                    return stream;
                }
                @Override void close(java.io.InputStream stream) throws java.io.IOException {
                    if (failedStreams.contains(stream)) throw new java.io.IOException("app descriptor cleanup failed");
                    super.close(stream);
                }
            });
        var candidate = driver.createCandidate(singleEntrySlice(appSource, new PluginFacetSource(base, List.of()), appSource));
        var failure = assertThrows(JavaRuntimeException.class, () -> candidate.prepareAsync().block());
        org.junit.jupiter.api.Assertions.assertSame(failure,
            assertThrows(JavaRuntimeException.class, () -> candidate.closeAsync().block()));
        assertEquals(0, closes.get(), "failed inner query cleanup must retain the dependency loader");
        assertEquals(1, descriptorReadCount(driver));
        var acquisitionsField = JavaRuntimeDriver.class.getDeclaredField("acquisitions");
        acquisitionsField.setAccessible(true); assertEquals(1, ((Set<?>) acquisitionsField.get(driver)).size());
        assertThrows(IllegalStateException.class, () -> driver.closeAsync().block());
        for (var handle : handles.reversed()) handle.close();
        var wiringField = JavaRuntimeDriver.class.getDeclaredField("wiringPool"); wiringField.setAccessible(true);
        for (var wiring : ((Map<?, ?>) wiringField.get(driver)).values()) {
            var loaderField = wiring.getClass().getDeclaredField("loader"); loaderField.setAccessible(true);
            ((PluginClassLoader) loaderField.get(wiring)).close();
        }
    }

    @Test
    void closeWaitsForAdmittedReadBeforeItsWorkerStarts() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var closed = new java.util.concurrent.CountDownLatch(1);
        var testThread = Thread.currentThread();
        var hook = "java-definition-query-admission";
        reactor.core.scheduler.Schedulers.onScheduleHook(hook, task -> {
            if (Thread.currentThread() != testThread) return task;
            return () -> {
                entered.countDown();
                try {
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("worker gate timeout");
                } catch (InterruptedException failure) { throw new AssertionError(failure); }
                task.run();
            };
        });
        var driver = queryDriver(new JavaFacetDescriptorReader.ResourceAccess());
        var query = driver.createDefinitionQuery(facet("queued-query", "entrypoint: missing.Entry\ndefinitionId: sample\n", null));
        var failure = new AtomicReference<Throwable>();
        try {
            var reading = query.readAsync().subscribe(ignored -> { }, failure::set);
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            reading.dispose();
            query.closeAsync().subscribe(ignored -> { }, error -> { failure.set(error); closed.countDown(); }, closed::countDown);
            assertEquals(1, closed.getCount(), "admitted read must reach its real terminal before close");
            assertEquals(Set.of(descriptorOwner(query)), indexedOwners(driver));
            release.countDown();
            assertTrue(closed.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(List.of("sample"), query.readAsync().block());
            assertEquals(0, descriptorReadCount(driver)); driver.closeAsync().block();
        } finally {
            release.countDown(); reactor.core.scheduler.Schedulers.resetOnScheduleHook(hook);
        }
    }

    @Test
    void manifestClassPathIsRejectedRegardlessOfEntryCase() throws Exception {
        for (var location : List.of("META-INF/MANIFEST.MF", "meta-inf/manifest.mf")) {
            var facet = facet("manifest-query", "{}\n", null);
            try (var jar = new JarOutputStream(Files.newOutputStream(facet.facet().payload()))) {
                entry(jar, location, "Manifest-Version: 1.0\r\nClass-Path: hidden.jar\r\n\r\n");
                entry(jar, JavaFacetDescriptorReader.LOCATION, "{}\n");
            }
            var driver = queryDriver(new JavaFacetDescriptorReader.ResourceAccess());
            var query = driver.createDefinitionQuery(facet);
            assertThrows(JavaRuntimeException.class, () -> query.readAsync().block());
            query.closeAsync().block(); driver.closeAsync().block();
        }
    }

    @Test
    void queryDoesNotExecuteGeneratedStaticConstructorMetadataOrFactory() throws Exception {
        var source = work.resolve("fixture/ObservedEntrypoint.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package fixture;
            public final class ObservedEntrypoint implements com.sstlfsj.fibra.PluginEntrypoint<Void> {
                static { fixture.PreparationObserver.callback.accept(ObservedEntrypoint.class.getClassLoader()); }
                public ObservedEntrypoint() { fixture.PreparationObserver.callback.accept(getClass().getClassLoader()); }
                public com.sstlfsj.fibra.PluginDefinition<Void> definition() {
                    fixture.PreparationObserver.callback.accept(getClass().getClassLoader());
                    return com.sstlfsj.fibra.PluginDefinition.builder("sample", Void.class, () -> {
                        fixture.PreparationObserver.callback.accept(getClass().getClassLoader());
                        return (context, config) -> reactor.core.publisher.Mono.empty();
                    }).build();
                }
            }
            """);
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-classpath", System.getProperty("java.class.path"), "-d", work.toString(), source.toString()));
        var facet = facet("generated-query", "{}\n", null);
        try (var jar = new JarOutputStream(Files.newOutputStream(facet.facet().payload()))) {
            entry(jar, JavaFacetDescriptorReader.LOCATION, "entrypoint: fixture.ObservedEntrypoint\ndefinitionId: sample\n");
            jar.putNextEntry(new JarEntry("fixture/ObservedEntrypoint.class"));
            jar.write(Files.readAllBytes(work.resolve("fixture/ObservedEntrypoint.class"))); jar.closeEntry();
        }
        var effects = new AtomicInteger();
        fixture.PreparationObserver.callback = ignored -> effects.incrementAndGet();
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of(), getClass().getClassLoader(),
            List.of("java.", "com.sstlfsj.fibra.", "reactor.", "fixture.PreparationObserver"), PluginClassLoader::close);
        var query = driver.createDefinitionQuery(facet);
        var candidate = driver.createCandidate(slice(new PluginFacetSource(facet, List.of())));
        try {
            assertEquals(List.of("sample"), query.readAsync().block());
            assertEquals(0, effects.get(), "no generated target path may execute during catalog reading");
            query.closeAsync().block();
            assertEquals(0, effects.get());
            candidate.prepareAsync().block();
            assertTrue(effects.get() >= 3, "real prepare must initialize, construct and read metadata from generated JAR");
            candidate.closeAsync().block(); driver.closeAsync().block();
        } finally { fixture.PreparationObserver.callback = ignored -> { }; }
    }

    @Test
    void descriptorReadFailureStillCleansIndependentManifestStreamAndRetainsJar() throws Exception {
        var facet = facet("two-streams", "{}\n", null);
        try (var jar = new JarOutputStream(Files.newOutputStream(facet.facet().payload()))) {
            entry(jar, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n");
            entry(jar, JavaFacetDescriptorReader.LOCATION, "{}\n");
        }
        var actual = new ArrayList<java.io.Closeable>();
        var closes = new ArrayList<String>();
        var streams = new java.util.IdentityHashMap<java.io.InputStream, String>();
        var driver = queryDriver(new JavaFacetDescriptorReader.ResourceAccess() {
            @Override java.util.jar.JarFile open(Path path) throws java.io.IOException {
                var jar = super.open(path); actual.add(jar); return jar;
            }
            @Override java.io.InputStream open(java.util.jar.JarFile jar, java.util.jar.JarEntry entry) throws java.io.IOException {
                var stream = super.open(jar, entry);
                if (entry.getName().equals(JavaFacetDescriptorReader.LOCATION)) {
                    stream = new java.io.FilterInputStream(stream) {
                        @Override public byte[] readNBytes(int n) { throw new AssertionError("read failure graph"); }
                    };
                }
                actual.add(stream); streams.put(stream, entry.getName()); return stream;
            }
            @Override void close(java.io.InputStream stream) throws java.io.IOException {
                var location = streams.get(stream); closes.add(location);
                if (location.equals(JavaFacetDescriptorReader.LOCATION)) throw new java.io.IOException("descriptor close failed");
                super.close(stream);
            }
            @Override void close(java.util.jar.JarFile jar) throws java.io.IOException {
                closes.add("jar"); super.close(jar);
            }
        });
        var query = driver.createDefinitionQuery(facet);
        var failure = assertThrows(JavaRuntimeException.class, () -> query.readAsync().block());
        assertNull(failure.getCause());
        assertThrows(JavaRuntimeException.class, () -> query.closeAsync().block());
        assertEquals(List.of(JavaFacetDescriptorReader.LOCATION, "META-INF/MANIFEST.MF"), closes);
        assertEquals(Set.of(descriptorOwner(query)), indexedOwners(driver));
        assertThrows(IllegalStateException.class, () -> driver.closeAsync().block());
        for (var handle : actual.reversed()) handle.close();
    }

    @Test
    void normalInspectProbeAndPrepareRetainTheirFailedDescriptorCleanupOwner() throws Exception {
        for (var path : List.of("inspect", "probe", "prepare")) {
            var facet = facet("normal-owner", "{}\n", null);
            var handles = new ArrayList<java.io.Closeable>();
            var streamCloses = new AtomicInteger(); var jarCloses = new AtomicInteger();
            var driver = queryDriver(new JavaFacetDescriptorReader.ResourceAccess() {
                @Override java.util.jar.JarFile open(Path path) throws java.io.IOException {
                    var jar = super.open(path); handles.add(jar); return jar;
                }
                @Override java.io.InputStream open(java.util.jar.JarFile jar, java.util.jar.JarEntry entry) throws java.io.IOException {
                    var stream = super.open(jar, entry); handles.add(stream); return stream;
                }
                @Override void close(java.io.InputStream stream) throws java.io.IOException {
                    streamCloses.incrementAndGet(); throw new java.io.IOException("descriptor cleanup failed");
                }
                @Override void close(java.util.jar.JarFile jar) throws java.io.IOException { jarCloses.incrementAndGet(); super.close(jar); }
            });
            var source = new PluginFacetSource(facet, List.of());
            if (path.equals("inspect")) assertThrows(JavaRuntimeException.class, () -> driver.inspect(facet).block());
            else if (path.equals("probe")) assertThrows(JavaRuntimeException.class, () -> driver.probe(source).block());
            else {
                var candidate = driver.createCandidate(slice(source));
                var prepareFailure = assertThrows(JavaRuntimeException.class, () -> candidate.prepareAsync().block());
                var closeFailure = assertThrows(JavaRuntimeException.class, () -> candidate.closeAsync().block());
                org.junit.jupiter.api.Assertions.assertSame(prepareFailure, closeFailure);
                org.junit.jupiter.api.Assertions.assertSame(closeFailure,
                    assertThrows(JavaRuntimeException.class, () -> candidate.closeAsync().block()));
            }
            assertEquals(1, descriptorReadCount(driver));
            var owner = indexedOwners(driver).iterator().next();
            assertThrows(JavaRuntimeException.class, () -> closeDescriptorOwner(owner));
            assertEquals(1, streamCloses.get()); assertEquals(0, jarCloses.get());
            assertThrows(IllegalStateException.class, () -> driver.closeAsync().block());
            for (var handle : handles.reversed()) handle.close();
        }
    }

    private static Set<?> indexedOwners(JavaRuntimeDriver driver) throws Exception {
        var field = JavaRuntimeDriver.class.getDeclaredField("descriptorReads"); field.setAccessible(true);
        return Set.copyOf((Set<?>) field.get(driver));
    }

    private static Object descriptorOwner(com.sstlfsj.fibra.engine.runtime.RuntimeDefinitionQuery query) throws Exception {
        var field = query.getClass().getDeclaredField("owner"); field.setAccessible(true);
        return field.get(query);
    }

    @Test
    void definitionQueriesAreInertStaticAndReplayTheirReadAndClose() throws Exception {
        var facet = facet("static-query", "entrypoint: missing.Entrypoint\ndefinitionId: ' sample '\n", null);
        var opens = new AtomicInteger();
        var streams = new AtomicInteger();
        var jars = new AtomicInteger();
        var driver = queryDriver(new JavaFacetDescriptorReader.ResourceAccess() {
            @Override java.util.jar.JarFile open(Path path) throws java.io.IOException {
                opens.incrementAndGet(); return super.open(path);
            }
            @Override void close(java.io.InputStream stream) throws java.io.IOException {
                streams.incrementAndGet(); super.close(stream);
            }
            @Override void close(java.util.jar.JarFile jar) throws java.io.IOException {
                jars.incrementAndGet(); super.close(jar);
            }
        });
        var query = driver.createDefinitionQuery(facet);
        var unread = query.readAsync();
        assertEquals(0, opens.get());
        assertEquals(List.of(" sample "), unread.block());
        assertEquals(List.of(" sample "), query.readAsync().block());
        assertEquals(1, opens.get());
        assertEquals(0, streams.get());
        query.closeAsync().block(); query.closeAsync().block();
        assertEquals(1, streams.get()); assertEquals(1, jars.get());
        assertEquals(List.of(" sample "), unread.block());
        assertEquals(0, descriptorReadCount(driver));
        var owner = descriptorOwner(query);
        var resourcesField = owner.getClass().getDeclaredField("resources");
        resourcesField.setAccessible(true);
        var resources = resourcesField.get(owner);
        var jarField = resources.getClass().getDeclaredField("jar");
        jarField.setAccessible(true); assertNull(jarField.get(resources));
        var streamsField = resources.getClass().getDeclaredField("streams");
        streamsField.setAccessible(true); assertTrue(((List<?>) streamsField.get(resources)).isEmpty());
        driver.closeAsync().block();
    }

    @Test
    void contractQueryAndCloseBeforeReadAcquireNoUnexpectedResources() throws Exception {
        var contract = facet("contract-query", "{}\n", null);
        var driver = queryDriver(new JavaFacetDescriptorReader.ResourceAccess());
        var query = driver.createDefinitionQuery(contract);
        assertEquals(List.of(), query.readAsync().block());
        query.closeAsync().block();
        var missing = new ManagedFacet(contract.artifactId(), contract.pluginId(), contract.packageRevision(),
            new PluginFacet(contract.facet().facetId(), contract.facet().role(), contract.facet().runtimeId(),
                contract.facet().executionTarget(), work.resolve("absent.jar"), contract.facet().payloadDigest(), List.of(), List.of()));
        var inert = driver.createDefinitionQuery(missing);
        var deferred = inert.readAsync();
        inert.closeAsync().block();
        assertThrows(IllegalStateException.class, () -> deferred.block());
        assertEquals(0, descriptorReadCount(driver));
        driver.closeAsync().block();
        assertThrows(IllegalStateException.class, () -> driver.createDefinitionQuery(contract));
        assertThrows(NullPointerException.class, () -> driver.createDefinitionQuery(null));
    }

    @Test
    void definitionQueryUsesStrictDescriptorParser() throws Exception {
        for (var yaml : List.of("entrypoint: missing.Entry\n", "definitionId: sample\n",
            "entrypoint: 42\ndefinitionId: sample\n", "entrypoint: missing.Entry\ndefinitionId: 42\n",
            "entrypoint: missing.Entry\ndefinitionId: ''\n", "entrypoint: ''\ndefinitionId: sample\n",
            "entrypoint: missing.Entry\ndefinitionId: sample\ndefinitionId: sample\n",
            "entrypoint: missing.Entry\nentrypoint: missing.Entry\ndefinitionId: sample\n",
            "entrypoint: missing.Entry\ndefinitionId: sample\nunknown: true\n", "#".repeat(65537))) {
            var driver = queryDriver(new JavaFacetDescriptorReader.ResourceAccess());
            var query = driver.createDefinitionQuery(facet("invalid-query", yaml, null));
            var failure = assertThrows(JavaRuntimeException.class, () -> query.readAsync().block());
            assertEquals(failure, assertThrows(JavaRuntimeException.class, () -> query.readAsync().block()));
            assertNull(failure.getCause(), "cached failure must not retain original exception graph");
            query.closeAsync().block();
            assertEquals(0, descriptorReadCount(driver)); driver.closeAsync().block();
        }
        assertThrows(NullPointerException.class, () -> new JavaFacetDescriptor(null, java.util.Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new JavaFacetDescriptor(java.util.Optional.of("x"), java.util.Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new JavaFacetDescriptor(java.util.Optional.of("x"), java.util.Optional.of(" ")));
        assertEquals(" sample ", new JavaFacetDescriptor(java.util.Optional.of(" x "), java.util.Optional.of(" sample ")).definitionId().orElseThrow());
    }

    @Test
    void queryCleanupFailureRetainsItsSameOwnerWithoutRetry() throws Exception {
        for (var streamFails : List.of(true, false)) {
            for (var readFails : List.of(true, false)) {
                var streamCloses = new AtomicInteger(); var jarCloses = new AtomicInteger();
                var handles = new ArrayList<java.io.Closeable>();
                var driver = queryDriver(new JavaFacetDescriptorReader.ResourceAccess() {
                    @Override java.util.jar.JarFile open(Path path) throws java.io.IOException {
                        var jar = super.open(path); handles.add(jar); return jar;
                    }
                    @Override java.io.InputStream open(java.util.jar.JarFile jar, java.util.jar.JarEntry entry) throws java.io.IOException {
                        var stream = super.open(jar, entry); handles.add(stream); return stream;
                    }
                    @Override void close(java.io.InputStream stream) throws java.io.IOException {
                        streamCloses.incrementAndGet();
                        if (streamFails) throw new java.io.IOException("stream close failed");
                        super.close(stream);
                    }
                    @Override void close(java.util.jar.JarFile jar) throws java.io.IOException {
                        jarCloses.incrementAndGet(); throw new java.io.IOException("jar close failed");
                    }
                });
                var query = driver.createDefinitionQuery(facet("close-query", readFails ? "unknown: true\n"
                    : "entrypoint: missing.Entry\ndefinitionId: sample\n", null));
                if (readFails) assertThrows(JavaRuntimeException.class, () -> query.readAsync().block());
                else assertEquals(List.of("sample"), query.readAsync().block());
                var failure = assertThrows(JavaRuntimeException.class, () -> query.closeAsync().block());
                assertEquals(failure, assertThrows(JavaRuntimeException.class, () -> query.closeAsync().block()));
                assertEquals(1, streamCloses.get()); assertEquals(streamFails ? 0 : 1, jarCloses.get());
                assertEquals(1, descriptorReadCount(driver));
                assertThrows(IllegalStateException.class, () -> driver.closeAsync().block());
                assertEquals(1, streamCloses.get());
                for (var handle : handles.reversed()) handle.close();
            }
        }
    }

    @Test
    void queryReadCancellationDoesNotReleaseRealReadAndCloseWaitsForItsTerminal() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var terminal = new java.util.concurrent.CountDownLatch(1);
        var closes = new AtomicInteger();
        var driver = queryDriver(new JavaFacetDescriptorReader.ResourceAccess() {
            @Override java.io.InputStream open(java.util.jar.JarFile jar, java.util.jar.JarEntry entry) throws java.io.IOException {
                var actual = super.open(jar, entry);
                return new java.io.FilterInputStream(actual) {
                    @Override public byte[] readNBytes(int n) throws java.io.IOException {
                        entered.countDown();
                        try {
                            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("read gate timeout");
                        } catch (InterruptedException failure) { throw new java.io.IOException(failure); }
                        return super.readNBytes(n);
                    }
                };
            }
            @Override void close(java.io.InputStream stream) throws java.io.IOException {
                closes.incrementAndGet(); super.close(stream);
            }
        });
        var query = driver.createDefinitionQuery(facet("gated-query", "entrypoint: missing.Entry\ndefinitionId: sample\n", null));
        var reading = query.readAsync().subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).subscribe();
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            reading.dispose();
            var closing = query.closeAsync().subscribe(ignored -> { }, failure -> terminal.countDown(), terminal::countDown);
            assertEquals(1, terminal.getCount()); assertEquals(0, closes.get());
            closing.dispose();
            query.closeAsync().subscribe(ignored -> { }, failure -> terminal.countDown(), terminal::countDown);
            release.countDown();
            assertTrue(terminal.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(List.of("sample"), query.readAsync().block());
            assertEquals(1, closes.get()); assertEquals(0, descriptorReadCount(driver));
            closing.dispose(); driver.closeAsync().block();
        } finally { release.countDown(); }
    }

    private JavaRuntimeDriver queryDriver(JavaFacetDescriptorReader.ResourceAccess access) {
        return new JavaRuntimeDriver(new NoopHostServices(), List.of(), getClass().getClassLoader(),
            List.of("java.", "com.sstlfsj.fibra.", "reactor."), PluginClassLoader::close, access);
    }

    private static int descriptorReadCount(JavaRuntimeDriver driver) throws Exception {
        var field = JavaRuntimeDriver.class.getDeclaredField("descriptorReads");
        field.setAccessible(true); return ((Set<?>) field.get(driver)).size();
    }

    @Test
    void rejectsMissingOrIsolatedDefinitionName() throws Exception {
        for (var descriptor : List.of("entrypoint: fixture.SampleEntrypoint\n",
            "definitionId: sample\n")) {
            var facet = facet("missing-name", descriptor, fixture.SampleEntrypoint.class);
            var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of());
            assertThrows(JavaRuntimeException.class, () -> driver.inspect(facet).block());
            driver.closeAsync().block();
        }
    }

    @Test
    void rejectsMetadataNameBeforeBindingAndClosesLoader() throws Exception {
        var facet = facet("name-mismatch", "entrypoint: fixture.PreparationEntrypoint\ndefinitionId: other\n",
            fixture.PreparationEntrypoint.class);
        var closes = new AtomicInteger();
        var metadata = new AtomicInteger();
        fixture.PreparationObserver.callback = ignored -> metadata.incrementAndGet();
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of(), getClass().getClassLoader(),
            List.of("java.", "com.sstlfsj.fibra.", "reactor.", "fixture.PreparationObserver"),
            loader -> { closes.incrementAndGet(); loader.close(); });
        var candidate = driver.createCandidate(slice(new PluginFacetSource(facet, List.of())));
        try {
            var failure = assertThrows(JavaRuntimeException.class, () -> candidate.prepareAsync().block());
            assertTrue(failure.getMessage().contains("definition"));
            assertEquals(1, metadata.get(), "real entrypoint metadata must have returned");
            candidate.closeAsync().block();
            assertEquals(1, closes.get());
            driver.closeAsync().block();
        } finally {
            fixture.PreparationObserver.callback = ignored -> { };
        }
    }

    @Test
    void activePluginFailureClosesAdmissionAndRequestsOneReplacement() {
        var health = reactor.core.publisher.Sinks.<Void>one();
        var mounted = new AtomicReference<PluginInstance<?>>();
        var definition = PluginDefinition.builder("supervised", Void.class,
            () -> (context, ignored) -> {
                mounted.set(context.plugins().current().orElseThrow());
                context.effects().supervise(health.asMono(), "health");
                return reactor.core.publisher.Mono.empty();
            }).build();
        var builtIn = builtIn(definition);
        var key = new ExecutionUnitKey("supervised-unit");
        var graph = new DesiredInputGraph(List.of(builtInEntry(key.value(), "supervised")));
        try (var services = new LiveHostServices()) {
            var driver = new JavaRuntimeDriver(services, List.of(builtIn));
            var generation = sealedBuiltIn(driver, builtIn, graph,
                Map.of(key, List.of())).generation();
            var unit = generation.units().get(key);
            assertEquals(com.sstlfsj.fibra.engine.observation.ExecutionObservation.State.ACTIVE,
                unit.reconcileAsync("activate").block().aggregateState());
            services.observationRefreshes.clear();

            health.tryEmitError(new IllegalStateException("health failed"));
            mounted.get().settled().onErrorResume(error ->
                reactor.core.publisher.Mono.empty()).block(java.time.Duration.ofSeconds(5));

            assertEquals(com.sstlfsj.fibra.engine.observation.ExecutionObservation.State.FAILED,
                unit.snapshot().aggregateState());
            assertEquals(List.of(Set.of(key)), services.reconcileRequests.stream()
                .map(request -> request.stream()
                    .map(com.sstlfsj.fibra.engine.execution.RuntimeUnitFence::unitKey)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()))
                .toList());
            assertThrows(IllegalStateException.class,
                () -> unit.reconcileAsync("cannot-restart-failed-unit").block());
            var identity = unit.snapshot().executions().getFirst().runtimeInstanceId();
            assertTrue(services.observationRefreshes.stream().anyMatch(fence ->
                fence.unitKey().equals(key) && fence.runtimeInstanceId().equals(identity)));
            stop(unit, "failed");
            generation.retireAsync().block();
            assertEquals(1, services.reconcileRequests.size());
            driver.closeAsync().block();
        }
    }

    @Test
    void generationObservationFreezesTheCompiledCapabilitySnapshot() {
        var definition = PluginDefinition.builder("capability-snapshot", Void.class,
            () -> (context, ignored) -> reactor.core.publisher.Mono.empty()).build();
        var builtIn = builtIn(definition);
        var key = new ExecutionUnitKey("capability-unit");
        var graph = new DesiredInputGraph(List.of(builtInEntry(key.value(),
            definition.name())));
        var target = DeploymentTarget.of(1, List.of(), graph,
            ConfigContextSnapshot.empty());
        var slice = RuntimeTargetSlice.builder(JavaRuntimeProvider.RUNTIME_ID,
                target)
            .desired(DesiredEvaluation.evaluate(graph, target.configContext()))
            .capabilities(HostCapabilitySnapshot.of(Map.of("host.storage", false)))
            .facets(List.of()).builtInPackages(List.of(builtIn.metadata()))
            .affectedEntryIds(Set.of(key.value()))
            .unitDependencies(Map.of(key, List.of())).build();
        try (var services = new LiveHostServices()) {
            var driver = new JavaRuntimeDriver(services, List.of(builtIn));
            var candidate = driver.createCandidate(slice);
            candidate.prepareAsync().block();
            var generation = candidate.seal(CompiledRuntimeSlice.of(
                candidate.preparedPlan(), List.of(key)));
            var unit = generation.units().get(key);

            assertEquals(Set.of("host.storage"), unit.reconcileAsync("activate")
                .block().executions().getFirst().capabilities());

            stop(unit, "capability");
            generation.retireAsync().block();
            driver.closeAsync().block();
        }
    }

    @Test
    void preparationBuildsAnEntryUnitWithoutStartingThePlugin() throws Exception {
        var facet = facet("app", "entrypoint: fixture.SampleEntrypoint\ndefinitionId: sample\n",
            fixture.SampleEntrypoint.class);
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of());
        var candidate = driver.createCandidate(slice(
            new PluginFacetSource(facet, List.of())));

        candidate.prepareAsync().block();

        var key = new ExecutionUnitKey("app");
        var plan = candidate.preparedPlan();
        assertEquals(JavaRuntimeProvider.RUNTIME_ID, plan.runtimeId());
        assertEquals(List.of(key), List.copyOf(plan.units().keySet()));
        assertEquals(facet.artifactId(), plan.units().get(key).artifactId());
        assertTrue(plan.units().get(key).dependencies().isEmpty());

        var generation = candidate.seal(CompiledRuntimeSlice.of(plan, List.of(key)));
        assertTrue(driver.snapshot().units().isEmpty());
        assertEquals(List.of(key), List.copyOf(generation.units().keySet()));
        generation.abortAsync().block();
        assertTrue(driver.snapshot().units().isEmpty());
        candidate.closeAsync().block();
        driver.closeAsync().block();
    }

    @Test
    void preparedPlanUsesDesiredEntryIdsAndEngineDependencies() throws Exception {
        var base = facet("base", "entrypoint: fixture.SampleEntrypoint\ndefinitionId: sample\n", fixture.SampleEntrypoint.class);
        var app = facet("app", "entrypoint: fixture.SampleEntrypoint\ndefinitionId: sample\n", fixture.SampleEntrypoint.class);
        var baseSource = new PluginFacetSource(base, List.of());
        var appSource = new PluginFacetSource(app, List.of(
            new ResolvedFacetDependency(base.pluginId(), base.packageRevision(),
                base.facet().facetId(), base.artifactId())));
        var candidate = new JavaRuntimeDriver(new NoopHostServices(), List.of()).createCandidate(slice(baseSource,
            appSource));

        candidate.prepareAsync().block();

        assertEquals(List.of(new ExecutionUnitKey("base")), candidate.preparedPlan()
            .units().get(new ExecutionUnitKey("app")).dependencies());
    }

    @Test
    void failedMetadataRetainsUnclosedLoaderAndCachesCleanupFailure() throws Exception {
        failedAcquisitionCleanup(0);
    }

    @Test
    void failedDescriptorRetainsUnclosedDependencyAndCachesCleanupFailure() throws Exception {
        failedAcquisitionCleanup(1);
    }

    @Test
    void failedParentCleanupDoesNotReleaseItsDependency() throws Exception {
        failedAcquisitionCleanup(2);
    }

    private void failedAcquisitionCleanup(int scenario) throws Exception {
        var base = facet("acquire-base", "{}\n", null,
            "c".repeat(64), "d".repeat(64));
        var app = facet("acquire-app", scenario == 1 ? "invalid: true\n"
            : "entrypoint: fixture.PreparationEntrypoint\ndefinitionId: sample\n",
            scenario == 1 ? null : fixture.PreparationEntrypoint.class);
        var baseSource = new PluginFacetSource(base, List.of());
        var appSource = new PluginFacetSource(app, scenario == 0 ? List.of()
            : List.of(dependency(base)));
        var closes = new ArrayList<PluginClassLoader>();
        var dependencyCloses = new AtomicInteger();
        fixture.PreparationObserver.callback = ignored -> {
            throw new IllegalStateException("injected metadata failure");
        };
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of(),
            getClass().getClassLoader(), List.of("java.", "com.sstlfsj.fibra.",
                "reactor.", "fixture.PreparationObserver"), loader -> {
                closes.add(loader);
                if (loader.getURLs()[0].equals(base.facet().payload().toUri().toURL())) {
                    dependencyCloses.incrementAndGet();
                }
                throw new java.io.IOException("injected cleanup failure");
            });
        var candidate = driver.createCandidate(singleEntrySlice(appSource,
            baseSource, appSource));
        try {
            assertThrows(RuntimeException.class, () -> candidate.prepareAsync().block());
            var cleanup = assertThrows(JavaRuntimeException.class,
                () -> candidate.closeAsync().block());
            assertEquals(JavaRuntimePhase.CLOSE, cleanup.phase());
            assertEquals(cleanup, assertThrows(JavaRuntimeException.class,
                () -> candidate.closeAsync().block()));
            assertEquals(1, closes.size(), "candidate cleanup must not retry");
            assertEquals(scenario == 1 ? 1 : 0, dependencyCloses.get(),
                "an unclosed parent must retain every dependency");
            assertThrows(IllegalStateException.class, () -> driver.closeAsync().block());
            assertEquals(1, closes.size(), "driver must reject before releasing owned loaders");
        } finally {
            fixture.PreparationObserver.callback = null;
            for (var loader : closes) loader.close();
        }
    }

    private static ResolvedFacetDependency dependency(ManagedFacet facet) {
        return new ResolvedFacetDependency(facet.pluginId(), facet.packageRevision(),
            facet.facet().facetId(), facet.artifactId());
    }

    @Test
    void nonfatalMetadataErrorsAreNormalizedAndKeepTheirCleanupOwner() throws Exception {
        for (var error : List.of(new AssertionError("metadata assertion"),
            new NoClassDefFoundError("metadata linkage"), new Error("custom nonfatal metadata error"))) {
            var facet = facet("error-app", "entrypoint: fixture.PreparationEntrypoint\ndefinitionId: sample\n",
                fixture.PreparationEntrypoint.class);
            var closes = new AtomicInteger();
            fixture.PreparationObserver.callback = ignored -> { throw error; };
            var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of(),
                getClass().getClassLoader(), List.of("java.", "com.sstlfsj.fibra.",
                    "reactor.", "fixture.PreparationObserver"), loader -> {
                    closes.incrementAndGet();
                    loader.close();
                    throw new java.io.IOException("cleanup failed");
                });
            var candidate = driver.createCandidate(slice(new PluginFacetSource(facet, List.of())));
            try {
                var failure = assertThrows(JavaRuntimeException.class,
                    () -> candidate.prepareAsync().block());
                assertEquals(error, failure.getCause());
                assertThrows(JavaRuntimeException.class, () -> candidate.closeAsync().block());
                assertThrows(IllegalStateException.class, () -> driver.closeAsync().block());
                assertEquals(1, closes.get());
            } finally {
                fixture.PreparationObserver.callback = null;
            }
        }
    }

    @Test
    void constructorVirtualMachineErrorEscapesWithoutCleanupAndRetainsItsOwner() throws Exception {
        fatalConstructorFailure(new OutOfMemoryError("prebuilt constructor fatal"));
    }

    @Test
    @SuppressWarnings("removal")
    void constructorThreadDeathEscapesWithoutCleanupAndRetainsItsOwner() throws Exception {
        fatalConstructorFailure(new ThreadDeath());
    }

    private void fatalConstructorFailure(Error fatal) throws Exception {
        var source = work.resolve("fixture/FatalConstructorEntrypoint.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package fixture;
            public final class FatalConstructorEntrypoint implements com.sstlfsj.fibra.PluginEntrypoint<Void> {
                public FatalConstructorEntrypoint() {
                    PreparationObserver.callback.accept(getClass().getClassLoader());
                }
                public com.sstlfsj.fibra.PluginDefinition<Void> definition() {
                    PreparationObserver.callback.accept(null);
                    throw new AssertionError("definition must not run");
                }
            }
            """);
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-classpath", System.getProperty("java.class.path"), "-d", work.toString(), source.toString()));
        var base = facet("constructor-base", "{}\n", null, "c".repeat(64), "d".repeat(64));
        var app = facet("constructor-app", "{}\n", null);
        try (var jar = new JarOutputStream(Files.newOutputStream(app.facet().payload()))) {
            entry(jar, JavaFacetDescriptorReader.LOCATION, "entrypoint: fixture.FatalConstructorEntrypoint\ndefinitionId: sample\n");
            jar.putNextEntry(new JarEntry("fixture/FatalConstructorEntrypoint.class"));
            jar.write(Files.readAllBytes(work.resolve("fixture/FatalConstructorEntrypoint.class")));
            jar.closeEntry();
        }
        var baseSource = new PluginFacetSource(base, List.of());
        var appSource = new PluginFacetSource(app, List.of(dependency(base)));
        var handles = new ArrayList<PluginClassLoader>();
        var closes = new AtomicInteger();
        var definitions = new AtomicInteger();
        fixture.PreparationObserver.callback = loader -> {
            if (loader == null) definitions.incrementAndGet();
            else {
                var parentLoader = (PluginClassLoader) loader;
                handles.add(parentLoader);
                try {
                    handles.addAll((List<PluginClassLoader>) field(parentLoader, "dependencies"));
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
                throw fatal;
            }
        };
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of(),
            getClass().getClassLoader(), List.of("java.", "com.sstlfsj.fibra.",
                "reactor.", "fixture.PreparationObserver"),
            loader -> { closes.incrementAndGet(); loader.close(); });
        var candidate = driver.createCandidate(singleEntrySlice(appSource, baseSource, appSource));
        try {
            var observed = assertThrows(Throwable.class, () -> candidate.prepareAsync().block());
            org.junit.jupiter.api.Assertions.assertAll(
                () -> org.junit.jupiter.api.Assertions.assertSame(fatal, observed),
                () -> assertEquals(0, closes.get(), "fatal acquisition must not enter cleanup"),
                () -> assertEquals(0, definitions.get()),
                () -> assertThrows(IllegalStateException.class, () -> driver.closeAsync().block()));
        } finally {
            fixture.PreparationObserver.callback = null;
            // Release test handles directly; the fatal candidate has no controlled close completion.
            for (var loader : handles) loader.close();
        }
    }

    @Test
    void driverRejectsAnAcquisitionBeforeItsWiringEntersThePool() throws Exception {
        var facet = facet("inflight-app", "entrypoint: fixture.PreparationEntrypoint\ndefinitionId: sample\n",
            fixture.PreparationEntrypoint.class);
        var closes = new AtomicInteger();
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of(),
            getClass().getClassLoader(), List.of("java.", "com.sstlfsj.fibra.",
                "reactor.", "fixture.PreparationObserver"),
            loader -> { closes.incrementAndGet(); loader.close(); });
        fixture.PreparationObserver.callback = ignored -> {
            assertThrows(IllegalStateException.class, () -> driver.closeAsync().block());
            assertEquals(0, closes.get());
            throw new IllegalStateException("metadata failed after close rejection");
        };
        try {
            var candidate = driver.createCandidate(slice(new PluginFacetSource(facet, List.of())));
            assertThrows(RuntimeException.class, () -> candidate.prepareAsync().block());
            candidate.closeAsync().block();
            assertEquals(1, closes.get());
        } finally {
            fixture.PreparationObserver.callback = null;
        }
    }

    @Test
    void partialDependencyFailureStillClosesIndependentSiblingLeases() throws Exception {
        partialDependencyFailure(false);
    }

    @Test
    void failedPartialCleanupContinuesReleasingIndependentSiblings() throws Exception {
        partialDependencyFailure(true);
    }

    private void partialDependencyFailure(boolean cleanupFails) throws Exception {
        var base = facet("partial-base", "{}\n", null, "c".repeat(64), "d".repeat(64));
        var peer = facet("partial-peer", "{}\n", null, "1".repeat(64), "2".repeat(64));
        var broken = facet("partial-broken", "entrypoint: 42\n", null,
            "e".repeat(64), "f".repeat(64));
        var app = facet("partial-app", "entrypoint: fixture.SampleEntrypoint\ndefinitionId: sample\n",
            fixture.SampleEntrypoint.class);
        var baseSource = new PluginFacetSource(base, List.of());
        var peerSource = new PluginFacetSource(peer, List.of());
        var brokenSource = new PluginFacetSource(broken, List.of());
        var appSource = new PluginFacetSource(app, List.of(
            dependency(base), dependency(peer), dependency(broken)));
        var closes = new ArrayList<String>();
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of(),
            getClass().getClassLoader(), List.of("java.", "com.sstlfsj.fibra.", "reactor."),
            loader -> {
                closes.add(loader.getURLs()[0].getPath());
                loader.close();
                if (cleanupFails && loader.getURLs()[0].getPath().equals(peer.facet().payload().toString())) {
                    throw new java.io.IOException("peer cleanup failed");
                }
            });
        var candidate = driver.createCandidate(singleEntrySlice(appSource,
            baseSource, peerSource, brokenSource, appSource));
        assertThrows(JavaRuntimeException.class, () -> candidate.prepareAsync().block());
        if (cleanupFails) {
            assertThrows(JavaRuntimeException.class, () -> candidate.closeAsync().block());
            assertThrows(IllegalStateException.class, () -> driver.closeAsync().block());
            var roots = (List<?>) field(field(candidate, "acquisition"), "roots");
            assertEquals(1, roots.size());
            var retained = (List<?>) field(roots.getFirst(), "children");
            assertEquals(1, retained.size(), "only the failed sibling may stay owned");
            assertEquals(peer.artifactId(), field(retained.getFirst(), "artifactId"));
        } else {
            candidate.closeAsync().block();
            driver.closeAsync().block();
        }
        assertEquals(List.of(peer.facet().payload().toString(), base.facet().payload().toString()), closes);
    }

    @Test
    void builtInBindingErrorsAreNormalized() {
        var definition = PluginDefinition.builder("sample", Void.class,
            () -> (context, config) -> reactor.core.publisher.Mono.empty()).build();
        var metadata = builtIn(definition).metadata();
        var builtIn = new JavaBuiltInPackage(metadata, Map.of(new FacetId("host"), List.of(
            new JavaDefinitionEntry<>(definition, ignored -> {
                throw new AssertionError("bind failed");
            }))));
        var key = new ExecutionUnitKey("bind-unit");
        var target = DeploymentTarget.of(1, List.of(),
            new DesiredInputGraph(List.of(builtInEntry(key.value(), "sample"))),
            ConfigContextSnapshot.empty());
        var slice = RuntimeTargetSlice.builder(JavaRuntimeProvider.RUNTIME_ID, target)
            .desired(DesiredEvaluation.evaluate(target.desiredGraph(), target.configContext()))
            .capabilities(HostCapabilitySnapshot.empty()).builtInPackages(List.of(metadata))
            .facets(List.of()).affectedEntryIds(Set.of(key.value()))
            .unitDependencies(Map.of(key, List.of())).build();
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of(builtIn));
        var candidate = driver.createCandidate(slice);
        assertThrows(JavaRuntimeException.class, () -> candidate.prepareAsync().block());
        candidate.closeAsync().block();
        driver.closeAsync().block();
    }

    @Test
    void dynamicBindingFailureRetainsItsAcquiredLeaseWithoutRetryingCleanup() throws Exception {
        failedDynamicPreparation(false);
    }

    @Test
    void dynamicPlanFailureRetainsItsAcquiredLeaseWithoutRetryingCleanup() throws Exception {
        failedDynamicPreparation(true);
    }

    private void failedDynamicPreparation(boolean failPlan) throws Exception {
        var source = work.resolve("fixture/BindingEntrypoint.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package fixture;
            public final class BindingEntrypoint implements com.sstlfsj.fibra.PluginEntrypoint<Integer> {
                public com.sstlfsj.fibra.PluginDefinition<Integer> definition() {
                    return com.sstlfsj.fibra.PluginDefinition.builder("sample", Integer.class,
                        () -> (context, config) -> reactor.core.publisher.Mono.empty()).build();
                }
            }
            """);
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-classpath", System.getProperty("java.class.path"), "-d", work.toString(), source.toString()));
        var facet = facet("binding-app", "{}\n", null);
        try (var jar = new JarOutputStream(Files.newOutputStream(facet.facet().payload()))) {
            entry(jar, JavaFacetDescriptorReader.LOCATION, "entrypoint: fixture.BindingEntrypoint\ndefinitionId: sample\n");
            jar.putNextEntry(new JarEntry("fixture/BindingEntrypoint.class"));
            jar.write(Files.readAllBytes(work.resolve("fixture/BindingEntrypoint.class")));
            jar.closeEntry();
        }
        var key = new ExecutionUnitKey("binding-app");
        var entry = DesiredInputEntry.builder(key.value(), new PluginDefinitionRef(
            facet.pluginId().value(), "host", "sample"))
            .config(com.sstlfsj.fibra.value.LiteralValue.of(failPlan ? 1 : Map.of("invalid", true))).build();
        var target = DeploymentTarget.of(1, List.of(new PluginSelection(facet.pluginId(),
            facet.packageRevision(), true)), new DesiredInputGraph(List.of(entry)), ConfigContextSnapshot.empty());
        var slice = RuntimeTargetSlice.builder(JavaRuntimeProvider.RUNTIME_ID, target)
            .desired(DesiredEvaluation.evaluate(target.desiredGraph(), target.configContext()))
            .capabilities(HostCapabilitySnapshot.empty())
            .facets(List.of(new PluginFacetSource(facet, List.of())))
            .affectedEntryIds(Set.of(key.value()))
            .unitDependencies(Map.of(key, failPlan ? List.of(key) : List.of())).build();
        var closes = new AtomicInteger();
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of(),
            getClass().getClassLoader(), List.of("java.", "com.sstlfsj.fibra.", "reactor."),
            loader -> {
                closes.incrementAndGet();
                loader.close();
                throw new java.io.IOException("dynamic cleanup failed");
            });
        var candidate = driver.createCandidate(slice);
        var preparation = assertThrows(RuntimeException.class, () -> candidate.prepareAsync().block());
        var cause = preparation instanceof JavaRuntimeException ? preparation.getCause() : preparation;
        if (failPlan) {
            assertTrue(cause instanceof IllegalArgumentException);
            assertTrue(cause.getMessage().contains("execution unit cannot depend on itself"));
        } else {
            var mismatch = org.junit.jupiter.api.Assertions.assertInstanceOf(
                tools.jackson.databind.exc.MismatchedInputException.class, cause);
            assertEquals(Integer.class, mismatch.getTargetType());
        }
        var cleanup = assertThrows(JavaRuntimeException.class, () -> candidate.closeAsync().block());
        assertEquals(JavaRuntimePhase.CLOSE, cleanup.phase());
        assertEquals(cleanup, assertThrows(JavaRuntimeException.class, () -> candidate.closeAsync().block()));
        assertEquals(1, closes.get());
        assertThrows(IllegalStateException.class, () -> driver.closeAsync().block());
    }

    @Test
    void candidateRetainsOnlyPreparedUnitsWhoseCleanupFailed() throws Exception {
        var first = facet("a-prepared", "entrypoint: fixture.SampleEntrypoint\ndefinitionId: sample\n",
            fixture.SampleEntrypoint.class, "c".repeat(64), "d".repeat(64));
        var second = facet("z-prepared", "entrypoint: fixture.SampleEntrypoint\ndefinitionId: sample\n",
            fixture.SampleEntrypoint.class, "e".repeat(64), "f".repeat(64));
        var closes = new ArrayList<String>();
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of(),
            getClass().getClassLoader(), List.of("java.", "com.sstlfsj.fibra.", "reactor."),
            loader -> {
                var path = loader.getURLs()[0].getPath();
                closes.add(path);
                loader.close();
                if (path.equals(second.facet().payload().toString())) {
                    throw new java.io.IOException("prepared cleanup failed");
                }
            });
        var candidate = driver.createCandidate(slice(new PluginFacetSource(first, List.of()),
            new PluginFacetSource(second, List.of())));
        candidate.prepareAsync().block();
        var failure = assertThrows(JavaRuntimeException.class, () -> candidate.closeAsync().block());
        assertEquals(Set.of(new ExecutionUnitKey("z-prepared")),
            ((Map<?, ?>) field(candidate, "prepared")).keySet());
        assertEquals(failure, assertThrows(JavaRuntimeException.class, () -> candidate.closeAsync().block()));
        assertEquals(List.of(second.facet().payload().toString(), first.facet().payload().toString()), closes);
        assertThrows(IllegalStateException.class, () -> driver.closeAsync().block());
    }

    @Test
    void diamondKeepsOneLeasePerEdgeAndTransfersWithoutExtraReferences() throws Exception {
        var base = facet("diamond-base", "{}\n", null, "c".repeat(64), "d".repeat(64));
        var left = facet("diamond-left", "{}\n", null, "e".repeat(64), "f".repeat(64));
        var right = facet("diamond-right", "{}\n", null, "1".repeat(64), "2".repeat(64));
        var app = facet("diamond-app", "entrypoint: fixture.SampleEntrypoint\ndefinitionId: sample\n",
            fixture.SampleEntrypoint.class);
        var baseSource = new PluginFacetSource(base, List.of());
        var leftSource = new PluginFacetSource(left, List.of(dependency(base)));
        var rightSource = new PluginFacetSource(right, List.of(dependency(base)));
        var appSource = new PluginFacetSource(app, List.of(dependency(left), dependency(right)));
        var closes = new ArrayList<String>();
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of(),
            getClass().getClassLoader(), List.of("java.", "com.sstlfsj.fibra.", "reactor."),
            loader -> { closes.add(loader.getURLs()[0].getPath()); loader.close(); });
        var candidate = driver.createCandidate(singleEntrySlice(appSource,
            baseSource, leftSource, rightSource, appSource));
        candidate.prepareAsync().block();
        var pool = (Map<?, ?>) field(driver, "wiringPool");
        assertEquals(4, pool.size());
        var baseWiring = pool.values().stream().filter(value -> {
            try { return ((ManagedFacet) field(value, "facet")).artifactId().equals(base.artifactId()); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }).findFirst().orElseThrow();
        assertEquals(2, field(baseWiring, "references"));
        var generation = candidate.seal(CompiledRuntimeSlice.of(candidate.preparedPlan(),
            List.of(new ExecutionUnitKey("diamond-app"))));
        candidate.closeAsync().block();
        assertEquals(0, closes.size());
        generation.abortAsync().block();
        assertEquals(List.of(app, right, left, base).stream()
            .map(value -> value.facet().payload().toString()).toList(), closes);
        assertTrue(pool.isEmpty());
        driver.closeAsync().block();
    }

    @Test
    void failedPreparationCanBeClosedRepeatedly() throws Exception {
        var facet = facet("invalid", "entrypoint: 42\n", null);
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of());
        var candidate = driver.createCandidate(slice(
            new PluginFacetSource(facet, List.of())));

        assertThrows(JavaRuntimeException.class,
            () -> candidate.prepareAsync().block());
        candidate.closeAsync().block();
        candidate.closeAsync().block();
        driver.closeAsync().block();
    }

    @Test
    void staticClassSpaceIsSharedAcrossAttemptsAndClosesAfterTheLastLease() throws Exception {
        var facet = facet("app", "entrypoint: fixture.PreparationEntrypoint\ndefinitionId: sample\n",
            fixture.PreparationEntrypoint.class);
        var definitions = new ArrayList<ClassLoader>();
        var closes = new ArrayList<PluginClassLoader>();
        fixture.PreparationObserver.callback = definitions::add;
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of(),
            getClass().getClassLoader(), List.of("java.", "com.sstlfsj.fibra.", "reactor.",
                "fixture.PreparationObserver"),
            loader -> { closes.add(loader); loader.close(); });
        try {
            var first = driver.createCandidate(slice(new PluginFacetSource(facet, List.of())));
            first.prepareAsync().block();
            var firstGeneration = first.seal(CompiledRuntimeSlice.of(first.preparedPlan(),
                List.of(new ExecutionUnitKey("app"))));
            var second = driver.createCandidate(slice(new PluginFacetSource(facet, List.of())));
            second.prepareAsync().block();
            var secondGeneration = second.seal(CompiledRuntimeSlice.of(second.preparedPlan(),
                List.of(new ExecutionUnitKey("app"))));

            assertEquals(1, definitions.size());
            firstGeneration.abortAsync().block();
            assertTrue(closes.isEmpty());
            secondGeneration.abortAsync().block();
            assertEquals(1, closes.size());
        } finally {
            fixture.PreparationObserver.callback = null;
            driver.closeAsync().block();
        }
    }

    @Test
    void stoppedUnitReleasesItsPrivateClassSpaceWhileItsGenerationKeepsARetainedPeer()
        throws Exception {
        var replacedFacet = facet("replaced",
            "entrypoint: fixture.PreparationEntrypoint\ndefinitionId: sample\n",
            fixture.PreparationEntrypoint.class);
        var retainedFacet = facet("retained",
            "entrypoint: fixture.PreparationEntrypoint\ndefinitionId: sample\n",
            fixture.PreparationEntrypoint.class,
            "c".repeat(64), "d".repeat(64));
        var closes = new ArrayList<PluginClassLoader>();
        fixture.PreparationObserver.callback = ignored -> { };
        try (var services = new LiveHostServices()) {
            var driver = new JavaRuntimeDriver(services, List.of(),
                getClass().getClassLoader(), List.of("java.",
                    "com.sstlfsj.fibra.", "reactor.",
                    "org.reactivestreams.", "org.slf4j.",
                    "fixture.PreparationObserver"),
                loader -> { closes.add(loader); loader.close(); });
            var candidate = driver.createCandidate(slice(
                new PluginFacetSource(replacedFacet, List.of()),
                new PluginFacetSource(retainedFacet, List.of())));
            candidate.prepareAsync().block();
            var generation = candidate.seal(CompiledRuntimeSlice.of(
                candidate.preparedPlan(), List.of(new ExecutionUnitKey("replaced"),
                    new ExecutionUnitKey("retained"))));
            var replaced = generation.units().get(
                new ExecutionUnitKey("replaced"));
            var retained = generation.units().get(
                new ExecutionUnitKey("retained"));
            replaced.reconcileAsync("start-replaced").block();
            retained.reconcileAsync("start-retained").block();

            stop(replaced, "replaced");

            assertEquals(1, closes.size());
            assertNull(field(replaced, "prepared"));
            assertNull(field(replaced, "instance"));
            assertNull(field(replaced, "scope"));
            assertNull(field(replaced, "contributions"));
            assertNull(field(replaced, "failure"));
            assertEquals(com.sstlfsj.fibra.engine.observation.ExecutionObservation.State.ACTIVE,
                retained.snapshot().aggregateState());

            stop(retained, "retained");
            generation.retireAsync().block();
            assertEquals(2, closes.size());
            driver.closeAsync().block();
        } finally {
            fixture.PreparationObserver.callback = null;
        }
    }

    @Test
    void builtInMetadataMustExactlyMatchPrivateDefinitions() {
        var facet = com.sstlfsj.fibra.engine.deployment.BuiltInFacet.builder(new FacetId("host"),
            JavaRuntimeProvider.RUNTIME_ID, new ExecutionTarget("host"))
            .definitionIds(Set.of("sample")).build();
        var metadata = com.sstlfsj.fibra.engine.deployment.BuiltInPluginPackage.builder()
            .pluginId(new PluginId("built-in")).version("1")
            .implementationDigest("a".repeat(64)).facets(List.of(facet)).build();
        var definition = PluginDefinition.builder("sample", Void.class,
            () -> (context, config) -> reactor.core.publisher.Mono.empty()).build();

        new JavaBuiltInPackage(metadata, Map.of(new FacetId("host"),
            List.of(new JavaDefinitionEntry<>(definition, ignored -> null))));
        var mismatched = new JavaBuiltInPackage(metadata,
            Map.of(new FacetId("host"), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> mismatched.definition(new FacetId("host"), "sample"));
    }

    @Test
    void builtInPrepareRejectsActualDefinitionSetMismatchBeforeBindingOrPublishingPlan() {
        var bindings = new java.util.concurrent.atomic.AtomicInteger();
        var declared = PluginDefinition.builder("sample", Void.class,
            () -> (context, config) -> reactor.core.publisher.Mono.empty()).build();
        var extra = PluginDefinition.builder("extra", Void.class,
            () -> (context, config) -> reactor.core.publisher.Mono.empty()).build();
        var metadata = builtIn(declared).metadata();
        var actual = new JavaBuiltInPackage(metadata, Map.of(new FacetId("host"), List.of(
            new JavaDefinitionEntry<>(declared, ignored -> { bindings.incrementAndGet(); return null; }),
            new JavaDefinitionEntry<>(extra, ignored -> { bindings.incrementAndGet(); return null; }))));
        var key = new ExecutionUnitKey("mismatched-built-in");
        var target = DeploymentTarget.of(1, List.of(),
            new DesiredInputGraph(List.of(builtInEntry(key.value(), "sample"))),
            ConfigContextSnapshot.empty());
        var slice = RuntimeTargetSlice.builder(JavaRuntimeProvider.RUNTIME_ID, target)
            .desired(DesiredEvaluation.evaluate(target.desiredGraph(), target.configContext()))
            .capabilities(HostCapabilitySnapshot.empty()).builtInPackages(List.of(metadata))
            .facets(List.of()).affectedEntryIds(Set.of(key.value()))
            .unitDependencies(Map.of(key, List.of())).build();
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of(actual));
        var candidate = driver.createCandidate(slice);
        try {
            assertThrows(JavaRuntimeException.class, () -> candidate.prepareAsync().block());
            assertEquals(0, bindings.get());
            assertThrows(IllegalStateException.class, candidate::preparedPlan);
        } finally {
            candidate.closeAsync().block();
            driver.closeAsync().block();
        }
    }

    @Test
    void runningJavaUnitPublishesItsOwnedRegistrarAndClosesRealRoutes()
        throws Exception {
        var facet = facet("contribution",
            "entrypoint: fixture.ContributionEntrypoint\ndefinitionId: sample\n",
            fixture.ContributionEntrypoint.class);
        var published = new AtomicReference<fixture.ContributionObserver.Published>();
        fixture.ContributionObserver.callback = published::set;
        try (var services = new LiveHostServices()) {
            var driver = new JavaRuntimeDriver(services, List.of(),
                getClass().getClassLoader(), List.of("java.", "com.sstlfsj.fibra.",
                    "reactor.", "org.reactivestreams.", "org.slf4j.",
                    "fixture.ContributionObserver"), PluginClassLoader::close);
            var candidate = driver.createCandidate(slice(
                new PluginFacetSource(facet, List.of())));
            candidate.prepareAsync().block();
            var plan = candidate.preparedPlan();
            var generation = candidate.seal(CompiledRuntimeSlice.of(plan,
                List.of(new ExecutionUnitKey("contribution"))));
            var unit = generation.units().get(
                new ExecutionUnitKey("contribution"));

            assertEquals(com.sstlfsj.fibra.engine.observation.ExecutionObservation.State.ACTIVE,
                unit.reconcileAsync("start").block().aggregateState());
            var registration = published.get();
            assertEquals(new com.sstlfsj.fibra.bridge.ContributionId(
                "contribution", "echo"), registration.registration().id());
            assertEquals("echo:request", services.directory.current().routes()
                .invoke(services.context(), registration.kind(),
                    registration.registration().id(),
                    registration.registration().registrationIdentity(), "request")
                .block());

            unit.closeAdmission();
            assertThrows(com.sstlfsj.fibra.bridge.ContributionUnavailableException.class,
                () -> services.directory.current().routes()
                    .invoke(services.context(), registration.kind(),
                        registration.registration().id(),
                        registration.registration().registrationIdentity(), "late")
                    .block());
            unit.drainAsync("drain", java.time.Instant.now().plusSeconds(5)).block();
            unit.stopAsync("stop", java.time.Instant.now().plusSeconds(5)).block();
            generation.retireAsync().block();
            driver.closeAsync().block();
        } finally {
            fixture.ContributionObserver.callback = null;
        }
    }

    @Test
    void failedStartRetainsScopeAndClassSpaceUntilFailedCleanupCanBeRetried()
        throws Exception {
        var facet = facet("failed-start",
            "entrypoint: fixture.ContributionEntrypoint\ndefinitionId: sample\n",
            fixture.ContributionEntrypoint.class);
        var startFailure = new IllegalStateException("injected start failure");
        fixture.ContributionObserver.callback = ignored -> { throw startFailure; };
        var closes = new ArrayList<PluginClassLoader>();
        try (var services = new LiveHostServices(true)) {
            var driver = new JavaRuntimeDriver(services, List.of(),
                getClass().getClassLoader(), List.of("java.",
                    "com.sstlfsj.fibra.", "reactor.",
                    "org.reactivestreams.", "org.slf4j.",
                    "fixture.ContributionObserver"),
                loader -> { closes.add(loader); loader.close(); });
            var candidate = driver.createCandidate(slice(
                new PluginFacetSource(facet, List.of())));
            candidate.prepareAsync().block();
            var generation = candidate.seal(CompiledRuntimeSlice.of(
                candidate.preparedPlan(),
                List.of(new ExecutionUnitKey("failed-start"))));
            var unit = generation.units().get(
                new ExecutionUnitKey("failed-start"));

            var observation = unit.reconcileAsync("start").block();

            assertEquals(com.sstlfsj.fibra.engine.observation.ExecutionObservation.State.FAILED,
                observation.aggregateState());
            assertEquals(startFailure.toString(),
                observation.executions().getFirst().failure().message());
            assertEquals(1, services.scopeCloseAttempts.get());
            assertEquals(1, startFailure.getSuppressed().length);
            assertEquals("injected scope close failure",
                startFailure.getSuppressed()[0].getMessage());
            assertTrue(closes.isEmpty(),
                "failed cleanup must retain the unit class-space lease");
            assertTrue(services.reconcileRequests.isEmpty(),
                "a unit that never became active must not enter an automatic replacement loop");

            unit.closeAdmission();
            unit.drainAsync("drain", java.time.Instant.now().plusSeconds(5))
                .block();
            unit.stopAsync("stop", java.time.Instant.now().plusSeconds(5))
                .block();
            generation.retireAsync().block();

            assertEquals(2, services.scopeCloseAttempts.get());
            assertEquals(1, closes.size());
            driver.closeAsync().block();
        } finally {
            fixture.ContributionObserver.callback = null;
        }
    }

    @Test
    void synchronousReleaseScopeFailuresRetainTheOriginalStartFailureAndLeaseUntilStopRetries()
        throws Exception {
        var limit = java.time.Duration.ofSeconds(5);
        for (var linkageStart : List.of(false, true)) {
            for (var linkageCleanup : List.of(false, true)) {
                var facet = facet("synchronous-cleanup-failure",
                    "entrypoint: fixture.ContributionEntrypoint\ndefinitionId: sample\n",
                    fixture.ContributionEntrypoint.class);
                Throwable startFailure = linkageStart
                    ? new NoClassDefFoundError("prebuilt start linkage failure")
                    : new IllegalStateException("prebuilt ordinary start failure");
                Throwable cleanupFailure = linkageCleanup
                    ? new NoClassDefFoundError("prebuilt releaseScope linkage failure")
                    : new IllegalStateException("prebuilt releaseScope ordinary failure");
                var starts = new AtomicInteger();
                fixture.ContributionObserver.start = context -> {
                    starts.incrementAndGet();
                    if (startFailure instanceof RuntimeException ordinary) throw ordinary;
                    throw (Error) startFailure;
                };
                var closes = new ArrayList<PluginClassLoader>();
                try (var services = new LiveHostServices(cleanupFailure)) {
                    var driver = new JavaRuntimeDriver(services, List.of(),
                        getClass().getClassLoader(), List.of("java.",
                            "com.sstlfsj.fibra.", "reactor.",
                            "org.reactivestreams.", "org.slf4j.",
                            "fixture.ContributionObserver"),
                        loader -> { closes.add(loader); loader.close(); });
                    var candidate = driver.createCandidate(slice(
                        new PluginFacetSource(facet, List.of())));
                    candidate.prepareAsync().block(limit);
                    var generation = candidate.seal(CompiledRuntimeSlice.of(
                        candidate.preparedPlan(),
                        List.of(new ExecutionUnitKey("synchronous-cleanup-failure"))));
                    var unit = generation.units().get(
                        new ExecutionUnitKey("synchronous-cleanup-failure"));

                    var observation = unit.reconcileAsync("start").block(limit);

                    assertEquals(com.sstlfsj.fibra.engine.observation.ExecutionObservation.State.FAILED,
                        observation.aggregateState());
                    assertEquals(startFailure.toString(),
                        observation.executions().getFirst().failure().message());
                    assertEquals(1, startFailure.getSuppressed().length);
                    assertSame(cleanupFailure, startFailure.getSuppressed()[0],
                        "the original start error owns the original cleanup error");
                    assertEquals(1, services.scopeCloseAttempts.get());
                    var retainedScope = services.synchronouslyFailedScope.get();
                    assertTrue(retainedScope != null);
                    assertFalse(retainedScope.isClosed(),
                        "synchronous releaseScope failure leaves the original Scope owned by the unit");
                    assertTrue(closes.isEmpty(), "failed cleanup retains its class-space lease");
                    assertEquals(1, starts.get());
                    assertTrue(services.reconcileRequests.isEmpty(),
                        "failed start must not schedule automatic replacement");
                    assertEquals(observation.executions().getFirst().runtimeInstanceId(),
                        unit.snapshot().executions().getFirst().runtimeInstanceId());

                    unit.closeAdmission();
                    unit.drainAsync("drain", java.time.Instant.now().plusSeconds(5)).block(limit);
                    assertFalse(retainedScope.isClosed());
                    assertTrue(closes.isEmpty(), "drain alone must not release the retained lease");
                    unit.stopAsync("stop", java.time.Instant.now().plusSeconds(5)).block(limit);

                    assertEquals(2, services.scopeCloseAttempts.get());
                    assertTrue(retainedScope.isClosed());
                    assertEquals(1, closes.size(), "successful stop releases its loader exactly once");
                    unit.stopAsync("stop-again", java.time.Instant.now().plusSeconds(5)).block(limit);
                    generation.retireAsync().block(limit);
                    generation.retireAsync().block(limit);
                    driver.closeAsync().block(limit);
                    assertEquals(2, services.scopeCloseAttempts.get());
                    assertEquals(1, closes.size());
                    assertEquals(1, starts.get());
                    assertTrue(services.reconcileRequests.isEmpty());
                } finally {
                    fixture.ContributionObserver.start = null;
                    fixture.ContributionObserver.callback = null;
                }
            }
        }
    }

    @Test
    void realScopeCleanupFailureRetainsTheUnitClassSpaceLease()
        throws Exception {
        var facet = facet("cleanup-failure",
            "entrypoint: fixture.ContributionEntrypoint\ndefinitionId: sample\n",
            fixture.ContributionEntrypoint.class);
        fixture.ContributionObserver.start = context ->
            context.effects().add(() -> reactor.core.publisher.Mono.error(
                new IllegalStateException("real cleanup failure")));
        var closes = new ArrayList<PluginClassLoader>();
        try (var services = new LiveHostServices()) {
            var driver = new JavaRuntimeDriver(services, List.of(),
                getClass().getClassLoader(), List.of("java.",
                    "com.sstlfsj.fibra.", "reactor.",
                    "org.reactivestreams.", "org.slf4j.",
                    "fixture.ContributionObserver"),
                loader -> { closes.add(loader); loader.close(); });
            var candidate = driver.createCandidate(slice(
                new PluginFacetSource(facet, List.of())));
            candidate.prepareAsync().block();
            var generation = candidate.seal(CompiledRuntimeSlice.of(
                candidate.preparedPlan(),
                List.of(new ExecutionUnitKey("cleanup-failure"))));
            var unit = generation.units().get(
                new ExecutionUnitKey("cleanup-failure"));

            assertEquals(com.sstlfsj.fibra.engine.observation.ExecutionObservation.State.ACTIVE,
                unit.reconcileAsync("start").block().aggregateState());
            unit.closeAdmission();
            unit.drainAsync("drain", java.time.Instant.now().plusSeconds(5))
                .block();

            var failure = assertThrows(IllegalStateException.class,
                () -> unit.stopAsync("stop",
                    java.time.Instant.now().plusSeconds(5)).block());

            assertTrue(failure.getMessage().contains(
                "runtime scope cleanup failed"));
            assertTrue(failure.getMessage().contains("real cleanup failure"));
            assertTrue(closes.isEmpty(),
                "scope cleanup failure must retain the unit class-space lease");
        } finally {
            fixture.ContributionObserver.start = null;
            fixture.ContributionObserver.callback = null;
        }
    }

    @Test
    void staticLeaseRetriesOnlyDependenciesWhoseReleaseFailed() throws Exception {
        var firstBase = facet("lease-base-first",
            "entrypoint: fixture.SampleEntrypoint\ndefinitionId: sample\n",
            fixture.SampleEntrypoint.class);
        var secondBase = facet("lease-base-second",
            "entrypoint: fixture.SampleEntrypoint\ndefinitionId: sample\n",
            fixture.SampleEntrypoint.class, "c".repeat(64), "d".repeat(64));
        var app = facet("lease-app",
            "entrypoint: fixture.SampleEntrypoint\ndefinitionId: sample\n",
            fixture.SampleEntrypoint.class);
        var firstBaseSource = new PluginFacetSource(firstBase, List.of());
        var secondBaseSource = new PluginFacetSource(secondBase, List.of());
        var appSource = new PluginFacetSource(app, List.of(
            new ResolvedFacetDependency(firstBase.pluginId(),
                firstBase.packageRevision(), firstBase.facet().facetId(),
                firstBase.artifactId()),
            new ResolvedFacetDependency(secondBase.pluginId(),
                secondBase.packageRevision(), secondBase.facet().facetId(),
                secondBase.artifactId())));
        var closeAttempts = new AtomicInteger();
        var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of(),
            getClass().getClassLoader(), List.of("java.",
                "com.sstlfsj.fibra.", "reactor."), loader -> {
                    if (closeAttempts.incrementAndGet() == 3) {
                        throw new java.io.IOException(
                            "injected dependency close failure");
                    }
                    loader.close();
                });
        var candidate = driver.createCandidate(singleEntrySlice(appSource,
            firstBaseSource, secondBaseSource, appSource));
        candidate.prepareAsync().block();
        var lease = preparedLease(candidate, new ExecutionUnitKey("lease-app"));

        assertThrows(JavaRuntimeException.class, lease::close);
        lease.close();

        assertEquals(4, closeAttempts.get(),
            "retry must skip the parent and the dependency already released");
        candidate.closeAsync().block();
        driver.closeAsync().block();
    }

    @Test
    void managedControlTargetsTheGeneratedRootInstanceAndRejectsAChild() {
        var root = new AtomicReference<PluginInstance<?>>();
        var child = new AtomicReference<PluginInstance<?>>();
        var childDefinition = PluginDefinition.builder("child", Void.class,
            () -> (context, ignored) -> reactor.core.publisher.Mono.empty())
            .require(ManagedPluginControl.KEY).build();
        var rootDefinition = PluginDefinition.builder("root", Void.class,
            () -> (context, ignored) -> {
                root.set(context.plugins().current().orElseThrow());
                context.plugins().requestDisable();
                var mounted = context.plugins().mount("child-instance",
                    childDefinition.prepare(null));
                child.set(mounted);
                return mounted.settled().then();
            }).require(ManagedPluginControl.KEY).build();
        var builtIn = builtIn(rootDefinition);

        try (var services = new LiveHostServices()) {
            var driver = new JavaRuntimeDriver(services, List.of(builtIn));
            var sealed = sealedBuiltIn(driver, builtIn, new DesiredInputGraph(
                List.of(builtInEntry("unit-root", "root"))),
                Map.of(new ExecutionUnitKey("unit-root"), List.of()));
            var unit = sealed.generation().units().get(
                new ExecutionUnitKey("unit-root"));
            var active = unit.reconcileAsync("start-root")
                .block(java.time.Duration.ofSeconds(2));
            assertEquals(com.sstlfsj.fibra.engine.observation.ExecutionObservation.State.ACTIVE,
                active.aggregateState());

            var request = services.disableRequest.get();
            assertEquals(JavaRuntimeProvider.RUNTIME_ID, request.fence().runtimeId());
            assertEquals(new ExecutionUnitKey("unit-root"), request.fence().unitKey());
            assertEquals(active.executions().getFirst().unitTargetRevision(),
                request.fence().unitTargetRevision());
            assertEquals(active.executions().getFirst().runtimeInstanceId(),
                request.fence().runtimeInstanceId());
            assertEquals("unit-root", root.get().id());
            assertNotEquals(root.get().id(), request.fence().runtimeInstanceId());
            assertEquals("java-managed-plugin-control", request.reason());
            assertThrows(FibraException.class,
                () -> child.get().context().plugins().requestDisable());
            assertEquals(request, services.disableRequest.get());

            stop(unit, "root");
            sealed.generation().retireAsync().block();
            driver.closeAsync().block();
        }
    }

    @Test
    void retainedConsumerReportsPendingWhenItsDynamicProviderIsRevoked() {
        var business = ServiceKey.of("java-test-business", String.class);
        var registrars = new ArrayList<Object>();
        var controls = new ArrayList<Object>();
        var consumed = new AtomicReference<String>();
        var provider = PluginDefinition.builder("provider", Void.class,
            () -> (context, ignored) -> {
                registrars.add(context.services().require(
                    ContributionServices.REGISTRAR));
                controls.add(context.services().require(ManagedPluginControl.KEY));
                context.services().provide(business, "shared-business");
                return reactor.core.publisher.Mono.empty();
            }).require(ContributionServices.REGISTRAR)
            .require(ManagedPluginControl.KEY).provide(business).build();
        var consumer = PluginDefinition.builder("consumer", Void.class,
            () -> (context, ignored) -> {
                registrars.add(context.services().require(
                    ContributionServices.REGISTRAR));
                controls.add(context.services().require(ManagedPluginControl.KEY));
                consumed.set(context.services().require(business));
                return reactor.core.publisher.Mono.empty();
            }).require(ContributionServices.REGISTRAR)
            .require(ManagedPluginControl.KEY).require(business).build();
        var builtIn = builtIn(provider, consumer);
        var providerKey = new ExecutionUnitKey("provider-unit");
        var consumerKey = new ExecutionUnitKey("consumer-unit");
        var providerGraph = new DesiredInputGraph(List.of(
            builtInEntryWithSharedControlRealms(providerKey.value(),
                "provider")));
        var consumerGraph = new DesiredInputGraph(List.of(
            builtInEntryWithSharedControlRealms(consumerKey.value(),
                "consumer")));

        try (var services = new LiveHostServices()) {
            var driver = new JavaRuntimeDriver(services, List.of(builtIn));
            var providerGeneration = sealedBuiltIn(driver, builtIn,
                providerGraph, Map.of(providerKey, List.of())).generation();
            var consumerGeneration = sealedBuiltIn(driver, builtIn,
                consumerGraph, Map.of(consumerKey, List.of())).generation();
            var providerUnit = providerGeneration.units().get(providerKey);
            var consumerUnit = consumerGeneration.units().get(consumerKey);

            assertEquals(com.sstlfsj.fibra.engine.observation.ExecutionObservation.State.ACTIVE,
                providerUnit.reconcileAsync("start-provider").block()
                    .aggregateState());
            assertEquals(com.sstlfsj.fibra.engine.observation.ExecutionObservation.State.ACTIVE,
                consumerUnit.reconcileAsync("start-consumer").block()
                    .aggregateState());
            assertEquals("shared-business", consumed.get());
            assertEquals(2, registrars.size());
            assertEquals(2, controls.size());
            assertNotSame(registrars.get(0), registrars.get(1));
            assertNotSame(controls.get(0), controls.get(1));

            var consumerIdentity = consumerUnit.snapshot().executions()
                .getFirst().runtimeInstanceId();
            stop(providerUnit, "provider");
            providerGeneration.retireAsync().block();
            var pending = consumerUnit.snapshot();
            assertEquals(com.sstlfsj.fibra.engine.observation.ExecutionObservation.State.PENDING,
                pending.aggregateState());
            assertEquals(consumerIdentity,
                pending.executions().getFirst().runtimeInstanceId());
            assertTrue(services.observationRefreshes.stream().anyMatch(fence ->
                fence.unitKey().equals(consumerKey)
                    && fence.runtimeInstanceId().equals(consumerIdentity)));

            services.observationRefreshes.clear();
            var restoredProviderGeneration = sealedBuiltIn(driver, builtIn,
                providerGraph, Map.of(providerKey, List.of())).generation();
            var restoredProvider = restoredProviderGeneration.units()
                .get(providerKey);
            assertEquals(com.sstlfsj.fibra.engine.observation.ExecutionObservation.State.ACTIVE,
                restoredProvider.reconcileAsync("restore-provider").block()
                    .aggregateState());
            var recovered = consumerUnit.snapshot();
            assertEquals(com.sstlfsj.fibra.engine.observation.ExecutionObservation.State.ACTIVE,
                recovered.aggregateState());
            assertEquals(consumerIdentity,
                recovered.executions().getFirst().runtimeInstanceId());
            assertTrue(services.observationRefreshes.stream().anyMatch(fence ->
                fence.unitKey().equals(consumerKey)
                    && fence.runtimeInstanceId().equals(consumerIdentity)));

            stop(restoredProvider, "restored-provider");
            restoredProviderGeneration.retireAsync().block();
            stop(consumerUnit, "consumer");
            consumerGeneration.retireAsync().block();

            services.observationRefreshes.clear();
            var postConsumerProviderGeneration = sealedBuiltIn(driver,
                builtIn, providerGraph, Map.of(providerKey, List.of()))
                .generation();
            var postConsumerProvider = postConsumerProviderGeneration.units()
                .get(providerKey);
            postConsumerProvider.reconcileAsync("post-consumer-provider")
                .block();
            assertTrue(services.observationRefreshes.stream().noneMatch(fence ->
                fence.unitKey().equals(consumerKey)));
            stop(postConsumerProvider, "post-consumer-provider");
            postConsumerProviderGeneration.retireAsync().block();
            driver.closeAsync().block();
        }
    }

    @Test
    void desiredRealmAndInterceptPoliciesAreFrozenPerUnit() {
        var business = ServiceKey.of("java-policy-business", String.class);
        var matchingValue = new AtomicReference<String>();
        var matchingIntercept = new AtomicReference<Object>();
        var isolatedStarts = new java.util.concurrent.atomic.AtomicInteger();
        var provider = PluginDefinition.builder("realm-provider", Void.class,
            () -> (context, ignored) -> {
                context.services().provide(business, "tenant-value");
                return reactor.core.publisher.Mono.empty();
            }).provide(business).build();
        var matching = PluginDefinition.builder("realm-matching", Void.class,
            () -> (context, ignored) -> {
                matchingValue.set(context.services().require(business));
                matchingIntercept.set(context.intercept(business));
                return reactor.core.publisher.Mono.empty();
            }).require(business).build();
        var isolated = PluginDefinition.builder("realm-isolated", Void.class,
            () -> (context, ignored) -> {
                isolatedStarts.incrementAndGet();
                return reactor.core.publisher.Mono.empty();
            }).require(business).build();
        var builtIn = builtIn(provider, matching, isolated);
        var providerKey = new ExecutionUnitKey("realm-provider-unit");
        var matchingKey = new ExecutionUnitKey("realm-matching-unit");
        var isolatedKey = new ExecutionUnitKey("realm-isolated-unit");
        var graph = new DesiredInputGraph(List.of(
            builtInEntry(providerKey.value(), "realm-provider", business,
                "tenant-a", null),
            builtInEntry(matchingKey.value(), "realm-matching", business,
                "tenant-a", Map.of("trace", "configured")),
            builtInEntry(isolatedKey.value(), "realm-isolated", business,
                "tenant-b", null)));

        try (var services = new LiveHostServices()) {
            var driver = new JavaRuntimeDriver(services, List.of(builtIn));
            var sealed = sealedBuiltIn(driver, builtIn, graph, Map.of(
                providerKey, List.of(), matchingKey, List.of(),
                isolatedKey, List.of()));
            var providerUnit = sealed.generation().units().get(providerKey);
            var matchingUnit = sealed.generation().units().get(matchingKey);
            var isolatedUnit = sealed.generation().units().get(isolatedKey);

            assertEquals(com.sstlfsj.fibra.engine.observation.ExecutionObservation.State.ACTIVE,
                providerUnit.reconcileAsync("start-provider").block()
                    .aggregateState());
            assertEquals(com.sstlfsj.fibra.engine.observation.ExecutionObservation.State.ACTIVE,
                matchingUnit.reconcileAsync("start-matching").block()
                    .aggregateState());
            assertEquals("tenant-value", matchingValue.get());
            assertEquals(Map.of("trace", "configured"),
                matchingIntercept.get());
            assertEquals(com.sstlfsj.fibra.engine.observation.ExecutionObservation.State.PENDING,
                isolatedUnit.reconcileAsync("start-isolated").block()
                    .aggregateState());
            assertEquals(0, isolatedStarts.get());

            stop(isolatedUnit, "isolated");
            stop(matchingUnit, "matching");
            stop(providerUnit, "provider");
            sealed.generation().retireAsync().block();
            driver.closeAsync().block();
        }
    }

    private RuntimeTargetSlice slice(PluginFacetSource... sources) {
        var facets = List.of(sources);
        var entries = facets.stream().map(PluginFacetSource::facet).map(facet ->
            DesiredInputEntry.builder(facet.artifactId().value(), new PluginDefinitionRef(
                facet.pluginId().value(), facet.facet().facetId().value(), "sample")).build()).toList();
        var target = DeploymentTarget.of(1,
            facets.stream().map(PluginFacetSource::facet).map(facet ->
                new PluginSelection(facet.pluginId(), facet.packageRevision(), true))
                .toList(),
            new DesiredInputGraph(entries), ConfigContextSnapshot.empty());
        var dependencies = new java.util.LinkedHashMap<ExecutionUnitKey, List<ExecutionUnitKey>>();
        for (var source : facets) {
            dependencies.put(new ExecutionUnitKey(source.facet().artifactId().value()),
                source.dependencies().stream().map(dependency ->
                    new ExecutionUnitKey(dependency.artifactId().value())).toList());
        }
        return RuntimeTargetSlice.builder(JavaRuntimeProvider.RUNTIME_ID, target)
            .desired(DesiredEvaluation.evaluate(target.desiredGraph(),
                target.configContext()))
            .capabilities(HostCapabilitySnapshot.empty())
            .facets(facets)
            .affectedEntryIds(Set.copyOf(target.desiredGraph().plugins().keySet()))
            .unitDependencies(dependencies)
            .build();
    }

    private RuntimeTargetSlice singleEntrySlice(PluginFacetSource unitSource,
                                                PluginFacetSource... sources) {
        var entryId = unitSource.facet().artifactId().value();
        var entry = DesiredInputEntry.builder(entryId,
            new PluginDefinitionRef(unitSource.facet().pluginId().value(),
                unitSource.facet().facet().facetId().value(), "sample"))
            .build();
        var target = DeploymentTarget.of(1,
            java.util.Arrays.stream(sources).map(PluginFacetSource::facet)
                .map(facet -> new PluginSelection(facet.pluginId(),
                    facet.packageRevision(), true)).toList(),
            new DesiredInputGraph(List.of(entry)),
            ConfigContextSnapshot.empty());
        return RuntimeTargetSlice.builder(JavaRuntimeProvider.RUNTIME_ID,
                target)
            .desired(DesiredEvaluation.evaluate(target.desiredGraph(),
                target.configContext()))
            .capabilities(HostCapabilitySnapshot.empty())
            .facets(List.of(sources)).affectedEntryIds(Set.of(entryId))
            .unitDependencies(Map.of(new ExecutionUnitKey(entryId), List.of()))
            .build();
    }

    private static AutoCloseable preparedLease(
        com.sstlfsj.fibra.engine.runtime.RuntimeCandidate candidate,
        ExecutionUnitKey key) throws Exception {
        var preparedField = candidate.getClass().getDeclaredField("prepared");
        preparedField.setAccessible(true);
        var prepared = (Map<?, ?>) preparedField.get(candidate);
        var unit = prepared.get(key);
        var leaseField = unit.getClass().getDeclaredField("lease");
        leaseField.setAccessible(true);
        return (AutoCloseable) leaseField.get(unit);
    }

    private static Object field(Object target, String name)
        throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static JavaBuiltInPackage builtIn(
        PluginDefinition<Void>... definitions) {
        var facetId = new FacetId("host");
        var facet = com.sstlfsj.fibra.engine.deployment.BuiltInFacet.builder(facetId,
                JavaRuntimeProvider.RUNTIME_ID, new ExecutionTarget("host"))
            .definitionIds(java.util.Arrays.stream(definitions)
                .map(PluginDefinition::name).collect(java.util.stream.Collectors.toSet()))
            .build();
        var metadata = com.sstlfsj.fibra.engine.deployment.BuiltInPluginPackage.builder()
            .pluginId(new PluginId("java-test-built-in")).version("1")
            .implementationDigest("d".repeat(64)).facets(List.of(facet)).build();
        var entries = new ArrayList<JavaDefinitionEntry<?>>();
        java.util.Arrays.stream(definitions).forEach(definition -> entries.add(
            new JavaDefinitionEntry<>(definition, ignored -> null)));
        return new JavaBuiltInPackage(metadata, Map.of(facetId, entries));
    }

    private static DesiredInputEntry builtInEntry(String entryId,
                                                  String definitionId) {
        return DesiredInputEntry.builder(entryId, new PluginDefinitionRef(
            "java-test-built-in", "host", definitionId)).build();
    }

    private static DesiredInputEntry builtInEntryWithSharedControlRealms(
        String entryId, String definitionId) {
        return DesiredInputEntry.builder(entryId, new PluginDefinitionRef(
                "java-test-built-in", "host", definitionId))
            .realms(Map.of(
                ContributionServices.REGISTRAR.name(),
                com.sstlfsj.fibra.value.LiteralValue.of("shared-control"),
                ManagedPluginControl.KEY.name(),
                com.sstlfsj.fibra.value.LiteralValue.of("shared-control")))
            .build();
    }

    private static DesiredInputEntry builtInEntry(
        String entryId, String definitionId, ServiceKey<?> service,
        String realm, Object intercept) {
        var builder = DesiredInputEntry.builder(entryId, new PluginDefinitionRef(
                "java-test-built-in", "host", definitionId))
            .realms(Map.of(service.name(),
                com.sstlfsj.fibra.value.LiteralValue.of(realm)));
        if (intercept != null) {
            builder.intercepts(Map.of(service.name(),
                com.sstlfsj.fibra.value.LiteralValue.of(intercept)));
        }
        return builder.build();
    }

    private static SealedBuiltIn sealedBuiltIn(JavaRuntimeDriver driver,
                                               JavaBuiltInPackage builtIn,
                                               DesiredInputGraph graph,
                                               Map<ExecutionUnitKey,
                                                   List<ExecutionUnitKey>> dependencies) {
        var target = DeploymentTarget.of(1, List.of(), graph,
            ConfigContextSnapshot.empty());
        var slice = RuntimeTargetSlice.builder(JavaRuntimeProvider.RUNTIME_ID,
                target)
            .desired(DesiredEvaluation.evaluate(graph, target.configContext()))
            .capabilities(HostCapabilitySnapshot.empty())
            .facets(List.of()).builtInPackages(List.of(builtIn.metadata()))
            .affectedEntryIds(Set.copyOf(graph.plugins().keySet()))
            .unitDependencies(dependencies).build();
        var candidate = driver.createCandidate(slice);
        candidate.prepareAsync().block();
        var dependencyFirst = List.copyOf(dependencies.keySet());
        var generation = candidate.seal(CompiledRuntimeSlice.of(
            candidate.preparedPlan(), dependencyFirst));
        return new SealedBuiltIn(generation);
    }

    private static void stop(com.sstlfsj.fibra.engine.runtime.RuntimeUnitGeneration unit,
                             String suffix) {
        unit.closeAdmission();
        unit.drainAsync("drain-" + suffix,
            java.time.Instant.now().plusSeconds(5)).block();
        unit.stopAsync("stop-" + suffix,
            java.time.Instant.now().plusSeconds(5)).block();
    }

    private record SealedBuiltIn(
        com.sstlfsj.fibra.engine.runtime.PreparedRuntimeGeneration generation) { }

    private static final class NoopHostServices implements RuntimeHostServices {
        @Override public String hostInstanceId() { return "java-test-host"; }
        @Override public com.sstlfsj.fibra.ScopeView scope() { return null; }
        @Override public reactor.core.publisher.Mono<Void> releaseScope(
            com.sstlfsj.fibra.Scope scope) {
            return reactor.core.publisher.Mono.defer(scope::closeAsync);
        }
        @Override public com.sstlfsj.fibra.bridge.ContributionKindRegistry contributionKinds() {
            return com.sstlfsj.fibra.bridge.ContributionKindRegistry.empty();
        }
        @Override public com.sstlfsj.fibra.bridge.ContributionAdmission openContributionAdmission(com.sstlfsj.fibra.engine.execution.ExecutionUnitKey key) {
            throw new UnsupportedOperationException();
        }
        @Override public com.sstlfsj.fibra.engine.publication.RemoteContributionInvoker remoteContributions() {
            throw new UnsupportedOperationException();
        }
        @Override public String nextIdentity(String namespace) { return namespace + ":test"; }
        @Override public void requestReconcile(
            Set<com.sstlfsj.fibra.engine.execution.RuntimeUnitFence> fences,
            String reason) { }
        @Override public void requestObservationRefresh(
            com.sstlfsj.fibra.engine.execution.RuntimeUnitFence fence) { }
        @Override public void requestDisable(
            com.sstlfsj.fibra.engine.runtime.RuntimeUnitDisableRequest request) { }
        @Override public void requestRecompile(com.sstlfsj.fibra.engine.runtime.RuntimeRecompileReason reason) { }
    }

    private static final class LiveHostServices
        implements RuntimeHostServices, AutoCloseable {
        private final com.sstlfsj.fibra.runtime.FibraRuntime runtime =
            com.sstlfsj.fibra.runtime.FibraRuntime.create();
        private final com.sstlfsj.fibra.runtime.RuntimeDomain domain =
            runtime.openDomain("java-driver-test");
        private final com.sstlfsj.fibra.bridge.ContributionDirectory directory =
            new com.sstlfsj.fibra.bridge.ContributionDirectory();
        private final AtomicReference<RuntimeUnitDisableRequest> disableRequest =
            new AtomicReference<>();
        private final List<com.sstlfsj.fibra.engine.execution.RuntimeUnitFence>
            observationRefreshes = new CopyOnWriteArrayList<>();
        private final List<Set<com.sstlfsj.fibra.engine.execution.RuntimeUnitFence>>
            reconcileRequests = new CopyOnWriteArrayList<>();
        private final boolean failFirstChildClose;
        private final Throwable synchronousScopeCloseFailure;
        private final AtomicInteger scopeCloseAttempts = new AtomicInteger();
        private final AtomicReference<com.sstlfsj.fibra.Scope> synchronouslyFailedScope =
            new AtomicReference<>();
        private long identities;

        private LiveHostServices() {
            this(false);
        }

        private LiveHostServices(boolean failFirstChildClose) {
            this.failFirstChildClose = failFirstChildClose;
            this.synchronousScopeCloseFailure = null;
        }

        private LiveHostServices(Throwable synchronousScopeCloseFailure) {
            this.failFirstChildClose = false;
            this.synchronousScopeCloseFailure = synchronousScopeCloseFailure;
        }

        @Override public String hostInstanceId() { return "java-live-host"; }
        @Override public com.sstlfsj.fibra.ScopeView scope() {
            var root = domain.rootScope().context().scope();
            if (!failFirstChildClose) return root;
            return new com.sstlfsj.fibra.ScopeView() {
                @Override public String name() { return root.name(); }
                @Override public com.sstlfsj.fibra.Context context() {
                    return root.context();
                }
                @Override public com.sstlfsj.fibra.Scope openChild(String name) {
                    return new FailOnceCloseScope(root.openChild(name),
                        scopeCloseAttempts);
                }
                @Override public boolean isClosed() { return root.isClosed(); }
            };
        }
        com.sstlfsj.fibra.Context context() {
            return domain.rootScope().context();
        }
        @Override public reactor.core.publisher.Mono<Void> releaseScope(
            com.sstlfsj.fibra.Scope scope) {
            if (synchronousScopeCloseFailure != null && scopeCloseAttempts.getAndIncrement() == 0) {
                synchronouslyFailedScope.set(scope);
                if (synchronousScopeCloseFailure instanceof RuntimeException ordinary) throw ordinary;
                throw (Error) synchronousScopeCloseFailure;
            }
            var owned = scope instanceof FailOnceCloseScope wrapped
                ? wrapped.delegate : scope;
            return reactor.core.publisher.Mono.defer(scope::closeAsync)
                .then(reactor.core.publisher.Mono.fromRunnable(() -> {
                    var failures = domain.cleanupFailures(owned);
                    if (!failures.isEmpty()) {
                        throw new IllegalStateException(
                            "runtime scope cleanup failed: " + failures);
                    }
                }));
        }
        @Override public com.sstlfsj.fibra.bridge.ContributionKindRegistry contributionKinds() {
            return com.sstlfsj.fibra.bridge.ContributionKindRegistry.empty();
        }
        @Override public com.sstlfsj.fibra.bridge.ContributionAdmission openContributionAdmission(com.sstlfsj.fibra.engine.execution.ExecutionUnitKey key) {
            return directory.openAdmission(key.value());
        }
        @Override public com.sstlfsj.fibra.engine.publication.RemoteContributionInvoker remoteContributions() {
            throw new UnsupportedOperationException();
        }
        @Override public String nextIdentity(String namespace) {
            return namespace + ':' + ++identities;
        }
        @Override public void requestReconcile(
            Set<com.sstlfsj.fibra.engine.execution.RuntimeUnitFence> fences,
            String reason) {
            reconcileRequests.add(Set.copyOf(fences));
        }
        @Override public void requestObservationRefresh(
            com.sstlfsj.fibra.engine.execution.RuntimeUnitFence fence) {
            observationRefreshes.add(fence);
        }
        @Override public void requestDisable(
            com.sstlfsj.fibra.engine.runtime.RuntimeUnitDisableRequest request) {
            disableRequest.set(request);
        }
        @Override public void requestRecompile(
            com.sstlfsj.fibra.engine.runtime.RuntimeRecompileReason reason) { }
        @Override public void close() {
            directory.close();
            runtime.close();
        }
    }

    private static final class FailOnceCloseScope
        implements com.sstlfsj.fibra.Scope {
        private final com.sstlfsj.fibra.Scope delegate;
        private final AtomicInteger attempts;

        private FailOnceCloseScope(com.sstlfsj.fibra.Scope delegate,
                                   AtomicInteger attempts) {
            this.delegate = delegate;
            this.attempts = attempts;
        }

        @Override public String name() { return delegate.name(); }
        @Override public com.sstlfsj.fibra.Context context() {
            return delegate.context();
        }
        @Override public com.sstlfsj.fibra.Scope openChild(String name) {
            return delegate.openChild(name);
        }
        @Override public boolean isClosed() { return delegate.isClosed(); }
        @Override public reactor.core.publisher.Mono<Void> closeAsync() {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("injected scope close failure");
            }
            return delegate.closeAsync();
        }
    }

    private ManagedFacet facet(String artifactId, String descriptor,
                               Class<?> entrypoint) throws Exception {
        return facet(artifactId, descriptor, entrypoint, "a".repeat(64),
            "b".repeat(64));
    }

    private ManagedFacet facet(String artifactId, String descriptor,
                               Class<?> entrypoint, String packageRevision,
                               String payloadDigest) throws Exception {
        var payload = work.resolve(artifactId + ".jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(payload))) {
            entry(jar, JavaFacetDescriptorReader.LOCATION, descriptor);
            if (entrypoint != null) {
                copyClass(jar, entrypoint);
            }
        }
        return new ManagedFacet(new ArtifactId(artifactId), new PluginId(artifactId),
            packageRevision, new PluginFacet(new FacetId("host"), FacetRole.HOST,
                JavaRuntimeProvider.RUNTIME_ID, new ExecutionTarget("host"), payload,
                payloadDigest, List.of(), List.of()));
    }

    private static void copyClass(JarOutputStream jar, Class<?> type) throws Exception {
        var name = type.getName().replace('.', '/') + ".class";
        jar.putNextEntry(new JarEntry(name));
        try (var input = type.getResourceAsStream('/' + name)) {
            jar.write(input.readAllBytes());
        }
        jar.closeEntry();
    }

    private static void entry(JarOutputStream jar, String name, String value)
        throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(value.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }
}
