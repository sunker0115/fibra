package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.bridge.ContributionCodec;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionKindRegistry;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteContributionInvokerTest {
    private static final ContributionId ID = new ContributionId("provider", "echo");

    @Test
    void resolvesTheRegisteredKindAndDelegatesEveryPublishedFence() {
        var codec = new StringCodec();
        var kind = ContributionKind.remote("echo", String.class, String.class,
            String.class, codec);
        var seenKind = new AtomicReference<ContributionKind<?, ?, ?>>();
        var published = new StubPublishedRuntime((revision, registration, actual, id, input) -> {
            assertEquals("view-7", revision);
            assertEquals(41L, registration);
            assertEquals(ID, id);
            assertEquals("request", input);
            seenKind.set(actual);
            return Mono.just("response");
        });
        var invoker = new RemoteContributionInvoker(
            ContributionKindRegistry.of(kind), published);

        var result = invoker.invoke("echo", ID, "view-7", 41,
            LiteralValue.of("request")).block();

        assertSame(kind, seenKind.get());
        assertEquals(LiteralValue.of("response"), result);
    }

    @Test
    void rejectsUnknownAndLocalOnlyKindsBeforeCallingPublishedRuntime() {
        var local = ContributionKind.local("local", String.class, String.class,
            String.class);
        var published = new StubPublishedRuntime((revision, registration, kind, id, input) -> {
            throw new AssertionError("published runtime must not be called");
        });
        var invoker = new RemoteContributionInvoker(
            ContributionKindRegistry.of(local), published);

        assertEquals(RemoteContributionInvocationException.Code.UNKNOWN_KIND,
            failure(() -> invoker.invoke("missing", ID, "view", 1,
                LiteralValue.of("request")).block()).code());
        assertEquals(RemoteContributionInvocationException.Code.KIND_NOT_REMOTE,
            failure(() -> invoker.invoke("local", ID, "view", 1,
                LiteralValue.of("request")).block()).code());
    }

    @Test
    void distinguishesInputAndOutputCodecFailuresWithoutChangingPublishedFailures() {
        var inputFailure = ContributionKind.remote("input", String.class,
            String.class, String.class, new StringCodec() {
                @Override public String decodeInput(LiteralValue input) {
                    throw new IllegalArgumentException("bad input");
                }
            });
        var outputFailure = ContributionKind.remote("output", String.class,
            String.class, String.class, new StringCodec() {
                @Override public LiteralValue encodeOutput(String output) {
                    throw new IllegalArgumentException("bad output");
                }
            });
        var stale = new PublishedRevisionConflictException("old", "new");
        var published = new StubPublishedRuntime((revision, registration, kind, id, input) -> {
            if (kind == outputFailure) return Mono.just("response");
            return Mono.error(stale);
        });
        var invoker = new RemoteContributionInvoker(
            ContributionKindRegistry.of(inputFailure, outputFailure,
                ContributionKind.remote("stale", String.class, String.class,
                    String.class, new StringCodec())), published);

        assertEquals(RemoteContributionInvocationException.Code.INPUT_CODEC,
            failure(() -> invoker.invoke("input", ID, "view", 1,
                LiteralValue.of("request")).block()).code());
        assertEquals(RemoteContributionInvocationException.Code.OUTPUT_CODEC,
            failure(() -> invoker.invoke("output", ID, "view", 1,
                LiteralValue.of("request")).block()).code());
        assertSame(stale, assertThrows(PublishedRevisionConflictException.class,
            () -> invoker.invoke("stale", ID, "old", 1,
                LiteralValue.of("request")).block()));
    }

    private static RemoteContributionInvocationException failure(Runnable invocation) {
        return assertThrows(RemoteContributionInvocationException.class, invocation::run);
    }

    @FunctionalInterface
    private interface Invocation {
        Mono<?> invoke(String revision, long registration,
                       ContributionKind<?, ?, ?> kind, ContributionId id,
                       Object input);
    }

    private record StubPublishedRuntime(Invocation invocation)
        implements PublishedRuntime {
        @Override public PublishedView current() { throw new UnsupportedOperationException(); }
        @Override public Flux<PublishedView> views() { return Flux.never(); }
        @Override @SuppressWarnings("unchecked")
        public <D, I, O> Mono<O> invoke(String revision, long registration,
                                       ContributionKind<D, I, O> kind,
                                       ContributionId id, I input) {
            return (Mono<O>) invocation.invoke(revision, registration, kind, id,
                input);
        }
    }

    private static class StringCodec
        implements ContributionCodec<String, String, String> {
        @Override public int schemaVersion() { return 1; }
        @Override public String decodeDescriptor(LiteralValue descriptor) {
            return ((LiteralValue.StringValue) descriptor).value();
        }
        @Override public LiteralValue encodeInput(String input) {
            return LiteralValue.of(input);
        }
        @Override public String decodeInput(LiteralValue input) {
            return ((LiteralValue.StringValue) input).value();
        }
        @Override public LiteralValue encodeOutput(String output) {
            return LiteralValue.of(output);
        }
        @Override public String decodeOutput(LiteralValue output) {
            return ((LiteralValue.StringValue) output).value();
        }
    }
}
