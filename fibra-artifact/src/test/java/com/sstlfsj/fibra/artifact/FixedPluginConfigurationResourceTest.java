package com.sstlfsj.fibra.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FixedPluginConfigurationResourceTest {
    private static final byte[] BYTES = {1, 2, 3};
    private static final String SECRET = "secret raw exception and path";

    @ParameterizedTest
    @ValueSource(strings = {"vm", "thread"})
    @SuppressWarnings("removal")
    void fatalErrorsPropagateButCannotLeaveFalseSuccessfulTerminalState(String kind) throws Exception {
        Error fatal = kind.equals("vm") ? new InternalError(SECRET) : new ThreadDeath();
        var closes = new AtomicInteger();
        var stream = new InputStream() {
            @Override
            public int read() { throw fatal; }
            @Override
            public void close() {
                closes.incrementAndGet();
                throw fatal;
            }
        };
        var resource = resource(() -> stream);
        assertSame(fatal, assertThrows(Error.class, resource::read));
        PluginConfigurationResourceTest.assertSafe(assertThrows(ArtifactException.class, resource::read));
        assertSame(fatal, assertThrows(Error.class, resource::close));
        PluginConfigurationResourceTest.assertSafe(assertThrows(ArtifactException.class, resource::close));
        assertSame(stream, retainedInput(resource));
        assertEquals(1, closes.get());
    }

    @Test
    void errorsAlsoKeepSafeReadAndCloseTerminalOutcomes() throws Exception {
        var closes = new AtomicInteger();
        var stream = new InputStream() {
            @Override
            public int read() { throw new AssertionError(SECRET); }
            @Override
            public void close() {
                closes.incrementAndGet();
                throw new AssertionError(SECRET);
            }
        };
        var resource = resource(() -> stream);
        PluginConfigurationResourceTest.assertSafe(assertThrows(ArtifactException.class, resource::read));
        PluginConfigurationResourceTest.assertSafe(assertThrows(ArtifactException.class, resource::read));
        PluginConfigurationResourceTest.assertSafe(assertThrows(ArtifactException.class, resource::close));
        PluginConfigurationResourceTest.assertSafe(assertThrows(ArtifactException.class, resource::close));
        assertSame(stream, retainedInput(resource));
        assertEquals(1, closes.get());
    }

    @Test
    void creationIsLazyAndRepeatedReadOpensOnceWithoutClosingEarly() throws Exception {
        var opens = new AtomicInteger();
        var closes = new AtomicInteger();
        var stream = new ByteArrayInputStream(BYTES) {
            @Override
            public void close() { closes.incrementAndGet(); }
        };
        var resource = resource(() -> {
            opens.incrementAndGet();
            return stream;
        });
        assertEquals(0, opens.get());
        var first = resource.read();
        first[0] = 4;
        assertArrayEquals(BYTES, resource.read());
        assertEquals(1, opens.get());
        assertEquals(0, closes.get());
        resource.close();
        resource.close();
        assertEquals(1, closes.get());
        assertArrayEquals(BYTES, resource.read());
        assertNull(retainedInput(resource));
    }

    @Test
    void readAndCloseFailureAreSafeTerminalOutcomesAndRetainStream() throws Exception {
        var closes = new AtomicInteger();
        var stream = new InputStream() {
            @Override
            public int read() throws IOException { throw new IOException(SECRET); }
            @Override
            public void close() throws IOException {
                closes.incrementAndGet();
                throw new IOException(SECRET);
            }
        };
        var resource = resource(() -> stream);
        PluginConfigurationResourceTest.assertSafe(assertThrows(ArtifactException.class, resource::read));
        assertSame(stream, retainedInput(resource));
        assertEquals(0, closes.get());
        PluginConfigurationResourceTest.assertSafe(assertThrows(ArtifactException.class, resource::close));
        assertSame(stream, retainedInput(resource));
        PluginConfigurationResourceTest.assertSafe(assertThrows(ArtifactException.class, resource::read));
        PluginConfigurationResourceTest.assertSafe(assertThrows(ArtifactException.class, resource::close));
        assertEquals(1, closes.get());
    }

    @Test
    void successfulCloseClearsStreamButRetainsSafeReadFailure() throws Exception {
        var closes = new AtomicInteger();
        var stream = new InputStream() {
            @Override
            public int read() { throw new IllegalStateException(SECRET); }
            @Override
            public void close() { closes.incrementAndGet(); }
        };
        var resource = resource(() -> stream);
        PluginConfigurationResourceTest.assertSafe(assertThrows(ArtifactException.class, resource::read));
        assertSame(stream, retainedInput(resource));
        resource.close();
        assertNull(retainedInput(resource));
        PluginConfigurationResourceTest.assertSafe(assertThrows(ArtifactException.class, resource::read));
        resource.close();
        assertEquals(1, closes.get());
    }

    @Test
    void closeWaitsForBlockedReadAndConcurrentReadReplaysWithoutSecondOpen() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closeStarted = new CountDownLatch(1);
        var opens = new AtomicInteger();
        var closes = new AtomicInteger();
        var stream = new ByteArrayInputStream(BYTES) {
            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("read was not released");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                return super.read(buffer, offset, length);
            }
            @Override
            public void close() { closes.incrementAndGet(); }
        };
        var resource = resource(() -> {
            opens.incrementAndGet();
            return stream;
        });
        try (var executor = Executors.newFixedThreadPool(3)) {
            var read = executor.submit(resource::read);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var close = executor.submit(() -> {
                closeStarted.countDown();
                resource.close();
            });
            var secondRead = executor.submit(resource::read);
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
            try {
                assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> close.get(100, TimeUnit.MILLISECONDS));
                assertEquals(0, closes.get());
            } finally {
                release.countDown();
            }
            assertArrayEquals(BYTES, read.get(5, TimeUnit.SECONDS));
            close.get(5, TimeUnit.SECONDS);
            assertArrayEquals(BYTES, secondRead.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            resource.close();
        }
        assertEquals(1, opens.get());
        assertEquals(1, closes.get());
    }

    @Test
    void closeBeforeReadDoesNotInvokeIo() throws Exception {
        var resource = resource(() -> { throw new AssertionError("must not open"); });
        resource.close();
        resource.close();
        assertThrows(IllegalStateException.class, resource::read);
    }

    @Test
    void absentAndNonregularFactsNeverInvokeIo() {
        var absent = new FixedPluginConfigurationResource(Path.of("unused"),
            ConfigurationResourceEvidence.absent(), path -> { throw new AssertionError("must not open"); });
        assertThrows(java.util.NoSuchElementException.class, absent::read);
        absent.close();
        var nonregular = new FixedPluginConfigurationResource(Path.of("unused"),
            ConfigurationResourceEvidence.nonregular(), path -> { throw new AssertionError("must not open"); });
        PluginConfigurationResourceTest.assertSafe(assertThrows(ArtifactException.class, nonregular::read));
        nonregular.close();
    }

    @Test
    void openingFailureIsNotRetriedOrLeaked() throws Exception {
        var opens = new AtomicInteger();
        var resource = resource(() -> {
            opens.incrementAndGet();
            throw new IOException(SECRET);
        });
        PluginConfigurationResourceTest.assertSafe(assertThrows(ArtifactException.class, resource::read));
        resource.close();
        PluginConfigurationResourceTest.assertSafe(assertThrows(ArtifactException.class, resource::read));
        assertEquals(1, opens.get());
    }

    private static FixedPluginConfigurationResource resource(Open open) throws Exception {
        var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES));
        return new FixedPluginConfigurationResource(Path.of("unused"),
            ConfigurationResourceEvidence.regular(hash, BYTES.length), path -> open.open());
    }

    private static InputStream retainedInput(FixedPluginConfigurationResource resource) throws Exception {
        var field = FixedPluginConfigurationResource.class.getDeclaredField("input");
        field.setAccessible(true);
        return (InputStream) field.get(resource);
    }

    @FunctionalInterface
    private interface Open {
        InputStream open() throws IOException;
    }
}
