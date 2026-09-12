package com.sstlfsj.fibra.plugins.tool;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolOutcomesTest {
    @Test
    void lazilyNormalizesSuccessWithoutChangingItsResult() {
        var calls = new AtomicInteger();
        var result = ToolResult.text("done");
        var outcome = ToolOutcomes.normalize(() -> {
            calls.incrementAndGet();
            return Mono.just(result);
        });
        assertEquals(0, calls.get());
        assertSame(result, assertInstanceOf(ToolOutcome.Success.class, outcome.block()).result());
        assertEquals(1, calls.get());
    }

    @Test
    void synchronousAndAsynchronousToolFailuresUseOneErrorAndContentProjection() {
        var exception = new ToolException(ToolFailureCode.NOT_FOUND, "missing");
        var synchronous = ToolOutcomes.normalize(() -> { throw exception; }).block();
        var asynchronous = ToolOutcomes.normalize(() -> Mono.error(exception)).block();
        assertEquals(synchronous, asynchronous);
        var failure = assertInstanceOf(ToolOutcome.Failure.class, synchronous);
        assertSame(exception.failure(), failure.error());
        assertEquals("missing", failure.error().message());
        assertEquals(List.of(ToolContent.text("Error: missing")), failure.content());
    }

    @Test
    void unknownFailuresPropagateUnchangedAndEmptyIsNotASuccess() {
        var failure = new IllegalStateException("transport unavailable");
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> ToolOutcomes.normalize(() -> { throw failure; }).block()));
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> ToolOutcomes.normalize(() -> Mono.error(failure)).block()));
        assertThrows(IllegalStateException.class, () -> ToolOutcomes.normalize(Mono::empty).block());
    }

    @Test
    void outcomesRejectNullsAndFreezeFailureContent() {
        var error = new ToolFailure(ToolFailureCode.IO_ERROR, "failed");
        var content = new ArrayList<ToolContent>(List.of(ToolContent.text("failure")));
        var outcome = new ToolOutcome.Failure(error, content);
        content.clear();
        assertEquals(List.of(ToolContent.text("failure")), outcome.content());
        assertThrows(UnsupportedOperationException.class, outcome.content()::clear);
        assertThrows(NullPointerException.class, () -> new ToolOutcome.Success(null));
        assertThrows(NullPointerException.class, () -> new ToolOutcome.Failure(null, List.of()));
        assertThrows(NullPointerException.class, () -> new ToolOutcome.Failure(error, null));
        assertThrows(NullPointerException.class, () -> new ToolFailure(null, "failed"));
        assertThrows(NullPointerException.class, () -> new ToolFailure(ToolFailureCode.IO_ERROR, null));
    }
}
