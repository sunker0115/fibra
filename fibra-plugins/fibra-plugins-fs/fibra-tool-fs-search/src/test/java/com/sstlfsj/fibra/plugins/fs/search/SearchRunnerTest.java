package com.sstlfsj.fibra.plugins.fs.search;

import com.sstlfsj.fibra.CancellationSource;
import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.plugins.subprocess.ProcessUnit;
import com.sstlfsj.fibra.plugins.subprocess.Subprocess;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessOutcome;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessOutput;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessServices;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessSpec;
import com.sstlfsj.fibra.plugins.tool.ToolException;
import com.sstlfsj.fibra.plugins.tool.ToolFailureCode;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
import com.sstlfsj.fibra.plugins.tool.ToolServices;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchRunnerTest {
    @Test
    void globUsesSubprocessAndBestEffortSpillsTheCompleteFormattedResult() {
        var unit = new TestUnit(outcome(0, "b.java\na.java\n", "", false));
        var spec = new AtomicReference<SubprocessSpec>();
        var spills = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.openDomain("search").rootScope().context();
            context.services().provide(SubprocessServices.SUBPROCESS, recording(unit, spec));
            context.services().provide(ToolServices.RESULT_SPILL_STORE,
                (invocation, name, content) -> {
                    spills.incrementAndGet();
                    assertEquals("glob-results.txt", name);
                    assertEquals("b.java\na.java", content);
                    return Mono.just("spill://glob");
                });
            var config = SearchPluginConfig.defaults("/opt/rg", "/workspace")
                .withLimits(SearchLimits.defaults().withGlobMaxResults(1));

            var result = new SearchRunner(config).glob(invocation(context),
                ToolRequest.of(Map.of("pattern", "*.java"))).block();

            assertEquals(1, spills.get());
            assertTrue(result.text().contains("Found 1 of 2 files"));
            assertTrue(result.text().contains("spill://glob"));
            assertEquals("/opt/rg", spec.get().argv().get(0));
            assertEquals("--no-config", spec.get().argv().get(1));
            assertEquals("/workspace", spec.get().cwd());
            assertEquals(20_000_000, spec.get().stdoutMaxBytes());
        }
    }

    @Test
    void grepParsesMatchesAndTreatsExitOneAsAnEmptySuccess() {
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.openDomain("search").rootScope().context();
            var match = "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"A.java\"},"
                + "\"lines\":{\"text\":\"needle\\n\"},\"line_number\":4}}\n";
            context.services().provide(SubprocessServices.SUBPROCESS,
                recording(new TestUnit(outcome(0, match, "", false)), new AtomicReference<>()));
            var runner = new SearchRunner(SearchPluginConfig.defaults("rg", "/workspace"));

            var found = runner.grep(invocation(context),
                ToolRequest.of(Map.of("pattern", "needle"))).block();
            assertTrue(found.text().contains("Line 4: needle"));
        }
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.openDomain("empty-search").rootScope().context();
            context.services().provide(SubprocessServices.SUBPROCESS,
                recording(new TestUnit(outcome(1, "", "", false)), new AtomicReference<>()));
            var empty = new SearchRunner(SearchPluginConfig.defaults("rg", "/workspace"))
                .grep(invocation(context),
                ToolRequest.of(Map.of("pattern", "absent"))).block();
            assertEquals("No matches found", empty.text());
        }
    }

    @Test
    void outputOverflowAndInvalidPatternsHaveStableFailureCodes() {
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.openDomain("search").rootScope().context();
            context.services().provide(SubprocessServices.SUBPROCESS,
                recording(new TestUnit(outcome(0, "partial", "", true)), new AtomicReference<>()));
            var runner = new SearchRunner(SearchPluginConfig.defaults("rg", "/workspace"));
            var overflow = assertThrows(ToolException.class, () -> runner.glob(invocation(context),
                ToolRequest.of(Map.of("pattern", "*"))).block());
            assertEquals(ToolFailureCode.OUTPUT_LIMIT, overflow.code());
        }
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.openDomain("invalid-search").rootScope().context();
            context.services().provide(SubprocessServices.SUBPROCESS,
                recording(new TestUnit(outcome(2, "", "regex parse error", false)),
                    new AtomicReference<>()));
            var invalid = assertThrows(ToolException.class,
                () -> new SearchRunner(SearchPluginConfig.defaults("rg", "/workspace"))
                    .grep(invocation(context), ToolRequest.of(Map.of("pattern", "["))).block());
            assertEquals(ToolFailureCode.INVALID_ARGUMENT, invalid.code());
        }
    }

    @Test
    void cancellationTerminatesAndWaitsForTheManagedUnit() {
        var source = new CancellationSource();
        var unit = new TestUnit(Mono.never());
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.openDomain("search").rootScope().context();
            context.services().provide(SubprocessServices.SUBPROCESS,
                (invocation, value) -> {
                    source.cancel();
                    return Mono.just(unit);
                });
            var failure = assertThrows(ToolException.class,
                () -> new SearchRunner(SearchPluginConfig.defaults("rg", "/workspace"))
                    .glob(invocation(context).withCancellation(source.token()),
                        ToolRequest.of(Map.of("pattern", "*"), source.token())).block());
            assertEquals(ToolFailureCode.ABORTED, failure.code());
            assertTrue(unit.terminated.get());
            assertTrue(unit.waited.get());
        }
    }

    @Test
    void cancellationWinsWhenSpawnCancelsThenReturnsASynchronouslyCompletedUnit() {
        var source = new CancellationSource();
        var unit = new TestUnit(outcome(0, "a.java\n", "", false));
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.openDomain("search-cancel-race").rootScope().context();
            context.services().provide(SubprocessServices.SUBPROCESS,
                (invocation, value) -> {
                    source.cancel();
                    return Mono.just(unit);
                });

            var failure = assertThrows(ToolException.class,
                () -> new SearchRunner(SearchPluginConfig.defaults("rg", "/workspace"))
                    .glob(invocation(context).withCancellation(source.token()),
                        ToolRequest.of(Map.of("pattern", "*"), source.token())).block());

            assertEquals(ToolFailureCode.ABORTED, failure.code());
            assertTrue(unit.terminated.get());
            assertTrue(unit.waited.get());
        }
    }

    @Test
    void synchronousSpawnFailureAfterCancellationIsAborted() {
        var source = new CancellationSource();
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.openDomain("search-cancel-spawn").rootScope().context();
            context.services().provide(SubprocessServices.SUBPROCESS,
                (invocation, value) -> {
                    source.cancel();
                    throw new IllegalStateException("spawn rejected cancellation");
                });

            var failure = assertThrows(ToolException.class,
                () -> new SearchRunner(SearchPluginConfig.defaults("rg", "/workspace"))
                    .glob(invocation(context).withCancellation(source.token()),
                        ToolRequest.of(Map.of("pattern", "*"), source.token())).block());

            assertEquals(ToolFailureCode.ABORTED, failure.code());
        }
    }

    @Test
    void timeoutTerminatesAndWaitsForTheManagedUnit() {
        var unit = new TestUnit(Mono.never());
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.openDomain("search").rootScope().context();
            context.services().provide(SubprocessServices.SUBPROCESS,
                recording(unit, new AtomicReference<>()));
            var config = SearchPluginConfig.defaults("rg", "/workspace")
                .withTiming(new SearchTiming(10L, 100L));
            var failure = assertThrows(ToolException.class,
                () -> new SearchRunner(config).glob(invocation(context),
                    ToolRequest.of(Map.of("pattern", "*"))).block(Duration.ofSeconds(2)));
            assertEquals(ToolFailureCode.TIMEOUT, failure.code());
            assertTrue(unit.terminated.get());
            assertTrue(unit.waited.get());
        }
    }

    @Test
    void timeoutCoversServiceAndSpawnAcquisition() {
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.openDomain("search-spawn-timeout").rootScope().context();
            context.services().provide(SubprocessServices.SUBPROCESS,
                (invocation, value) -> Mono.never());
            var config = SearchPluginConfig.defaults("rg", "/workspace")
                .withTiming(new SearchTiming(10L, 100L));

            var failure = assertThrows(ToolException.class,
                () -> new SearchRunner(config).glob(invocation(context),
                    ToolRequest.of(Map.of("pattern", "*"))).block(Duration.ofSeconds(2)));

            assertEquals(ToolFailureCode.TIMEOUT, failure.code());
        }
    }

    @Test
    void spillFailureDoesNotTurnASuccessfulSearchIntoFailure() {
        var unit = new TestUnit(outcome(0, "a\nb\n", "", false));
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.openDomain("search").rootScope().context();
            context.services().provide(SubprocessServices.SUBPROCESS,
                recording(unit, new AtomicReference<>()));
            context.services().provide(ToolServices.RESULT_SPILL_STORE,
                (invocation, name, content) -> Mono.error(new IllegalStateException("disk full")));
            var config = SearchPluginConfig.defaults("rg", "/workspace")
                .withLimits(SearchLimits.defaults().withGlobMaxResults(1));
            var result = new SearchRunner(config).glob(invocation(context),
                ToolRequest.of(Map.of("pattern", "*"))).block();
            assertTrue(result.text().contains("could not be saved"));
            assertFalse(unit.terminated.get());
        }
    }

    @Test
    void cancellationDuringSpillSupersedesTheSuccessfulSearch() {
        var source = new CancellationSource();
        var spillStarted = new AtomicBoolean();
        var unit = new TestUnit(outcome(0, "a\nb\n", "", false));
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.openDomain("search-cancel-spill").rootScope().context();
            context.services().provide(SubprocessServices.SUBPROCESS,
                recording(unit, new AtomicReference<>()));
            context.services().provide(ToolServices.RESULT_SPILL_STORE,
                (invocation, name, content) -> Mono.defer(() -> {
                    spillStarted.set(true);
                    source.cancel();
                    return Mono.just("spill://glob");
                }));
            var config = SearchPluginConfig.defaults("rg", "/workspace")
                .withLimits(SearchLimits.defaults().withGlobMaxResults(1));

            var failure = assertThrows(ToolException.class,
                () -> new SearchRunner(config).glob(
                    invocation(context).withCancellation(source.token()),
                    ToolRequest.of(Map.of("pattern", "*"), source.token())).block());

            assertTrue(spillStarted.get());
            assertEquals(ToolFailureCode.ABORTED, failure.code());
        }
    }

    @Test
    void synchronousSpillFailureDoesNotTurnASuccessfulSearchIntoFailure() {
        var unit = new TestUnit(outcome(0, "a\nb\n", "", false));
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.openDomain("search-sync-spill-failure").rootScope().context();
            context.services().provide(SubprocessServices.SUBPROCESS,
                recording(unit, new AtomicReference<>()));
            context.services().provide(ToolServices.RESULT_SPILL_STORE,
                (invocation, name, content) -> {
                    throw new IllegalStateException("disk unavailable");
                });
            var config = SearchPluginConfig.defaults("rg", "/workspace")
                .withLimits(SearchLimits.defaults().withGlobMaxResults(1));

            var result = new SearchRunner(config).glob(invocation(context),
                ToolRequest.of(Map.of("pattern", "*"))).block();

            assertTrue(result.text().contains("could not be saved"));
            assertFalse(unit.terminated.get());
        }
    }

    @Test
    void commandValidationUsesThePublicInvalidArgumentFailure() {
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.openDomain("search-invalid-arguments").rootScope().context();
            var failure = assertThrows(ToolException.class,
                () -> new SearchRunner(SearchPluginConfig.defaults("rg", "/workspace"))
                    .glob(invocation(context), ToolRequest.of(Map.of("pattern", " "))).block());

            assertEquals(ToolFailureCode.INVALID_ARGUMENT, failure.code());
        }
    }

    private static InvocationContext invocation(com.sstlfsj.fibra.Context context) {
        return InvocationContext.of(context, "fibra.tool");
    }

    private static Subprocess recording(TestUnit unit, AtomicReference<SubprocessSpec> spec) {
        return (invocation, value) -> {
            spec.set(value);
            return Mono.just(unit);
        };
    }

    private static Mono<SubprocessOutcome> outcome(int code, String stdout, String stderr,
                                                    boolean stdoutTruncated) {
        return Mono.just(SubprocessOutcome.builder()
            .exitCode(code)
            .stdout(output(stdout, stdoutTruncated))
            .stderr(output(stderr, false))
            .build());
    }

    private static SubprocessOutput output(String text, boolean truncated) {
        return SubprocessOutput.builder()
            .text(text)
            .truncated(truncated)
            .totalBytes(text.getBytes(StandardCharsets.UTF_8).length)
            .build();
    }

    private static final class TestUnit implements ProcessUnit {
        private final Mono<SubprocessOutcome> outcome;
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicBoolean waited = new AtomicBoolean();

        private TestUnit(Mono<SubprocessOutcome> outcome) {
            this.outcome = outcome;
        }

        @Override public long pid() { return 1; }
        @Override public Mono<SubprocessOutcome> done() { return outcome; }
        @Override public void terminate() { terminated.set(true); }
        @Override public Mono<Void> waitForExit() {
            return Mono.fromRunnable(() -> waited.set(true));
        }
        @Override public Mono<Void> drain() { return waitForExit(); }
        @Override public Mono<Void> dispose() { terminate(); return waitForExit(); }
    }
}
