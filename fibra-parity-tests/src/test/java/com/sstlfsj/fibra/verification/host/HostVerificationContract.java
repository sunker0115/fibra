package com.sstlfsj.fibra.verification.host;

import com.sstlfsj.fibra.bridge.ContributionCodec;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.value.LiteralValue;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 由动态 Java JAR 和宿主测试共同使用的父加载器契约。 */
public final class HostVerificationContract {
    public static final String KIND_NAME = "fibra.verification.host.echo";
    public static final ContributionKind<String, String, String> ECHO_KIND =
        ContributionKind.remote(KIND_NAME, String.class, String.class,
            String.class, new StringCodec());

    private static final AtomicInteger JAVA_STARTS = new AtomicInteger();
    private static volatile CountDownLatch heldEntered = new CountDownLatch(0);
    private static volatile Sinks.One<Void> heldRelease = Sinks.one();

    private HostVerificationContract() {
    }

    public static void reset() {
        JAVA_STARTS.set(0);
        heldEntered = new CountDownLatch(0);
        heldRelease = Sinks.one();
    }

    public static void javaStarted() {
        JAVA_STARTS.incrementAndGet();
    }

    public static int javaStarts() {
        return JAVA_STARTS.get();
    }

    public static void armHeldInvocation() {
        heldEntered = new CountDownLatch(1);
        heldRelease = Sinks.one();
    }

    public static Mono<String> javaReply(String prefix, String input) {
        if (!"hold".equals(input)) {
            return Mono.just(prefix + ':' + input);
        }
        heldEntered.countDown();
        return heldRelease.asMono().thenReturn(prefix + ':' + input);
    }

    public static boolean awaitHeldInvocation(long timeout, TimeUnit unit)
        throws InterruptedException {
        return heldEntered.await(timeout, unit);
    }

    public static void releaseHeldInvocation() {
        heldRelease.tryEmitEmpty();
    }

    private static final class StringCodec
        implements ContributionCodec<String, String, String> {
        @Override public int schemaVersion() { return 1; }
        @Override public String decodeDescriptor(LiteralValue descriptor) {
            return string(descriptor);
        }
        @Override public LiteralValue encodeInput(String input) {
            return LiteralValue.of(Objects.requireNonNull(input, "input"));
        }
        @Override public String decodeInput(LiteralValue input) {
            return string(input);
        }
        @Override public LiteralValue encodeOutput(String output) {
            return LiteralValue.of(Objects.requireNonNull(output, "output"));
        }
        @Override public String decodeOutput(LiteralValue output) {
            return string(output);
        }
        private static String string(LiteralValue value) {
            if (!(Objects.requireNonNull(value, "value")
                instanceof LiteralValue.StringValue text)) {
                throw new IllegalArgumentException("host verification requires a string literal");
            }
            return text.value();
        }
    }
}
