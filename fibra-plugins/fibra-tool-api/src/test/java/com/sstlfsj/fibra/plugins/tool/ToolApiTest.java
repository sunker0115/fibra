package com.sstlfsj.fibra.plugins.tool;

import com.sstlfsj.fibra.CancellationSource;
import com.sstlfsj.fibra.bridge.RemoteContributionFailure;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolApiTest {
    @Test
    void descriptorRequiresTextAndObjectSchemas() {
        var schema = object(Map.of("type", "object"));

        var descriptor = new ToolDescriptor("Read file", "Reads UTF-8 text", schema, schema);

        assertEquals("Read file", descriptor.displayName());
        assertThrows(IllegalArgumentException.class,
            () -> new ToolDescriptor(" ", "description", schema, schema));
        assertThrows(NullPointerException.class,
            () -> new ToolDescriptor("name", "description", null, schema));
    }

    @Test
    void requestOfFreezesItsArguments() {
        var nested = new ArrayList<Object>(List.of("before"));
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("items", nested);

        var request = ToolRequest.of(arguments);
        nested.set(0, "after");
        arguments.clear();

        assertEquals("{\"items\":[\"before\"]}", request.arguments().canonicalJson());
        assertInstanceOf(LiteralValue.ListValue.class, request.arguments().values().get("items"));
        assertFalse(request.cancellation().isCancelled());

        var source = new CancellationSource();
        var cancellable = ToolRequest.of(Map.of(), source.token());
        source.cancel();
        assertTrue(cancellable.cancellation().isCancelled());
    }

    @Test
    void resultFactoriesSeparateTextAbsenceAndExplicitNull() {
        var text = ToolResult.text("done");
        var structured = ToolResult.structured(LiteralValue.of(Map.of("count", 1)));

        assertEquals(List.of(ToolContent.text("done")), text.content());
        assertTrue(text.structuredContent().isEmpty());
        assertEquals(List.of(ToolContent.text("{\"count\":1}")), structured.content());
        assertEquals(LiteralValue.of(Map.of("count", 1)), structured.structuredContent().orElseThrow());
        assertEquals(List.of(ToolContent.text("null")), ToolResult.structured(LiteralValue.of(null)).content());
        assertEquals(Optional.of(LiteralValue.of(null)), ToolResult.structured(LiteralValue.of(null)).structuredContent());
        assertThrows(NullPointerException.class, () -> new ToolResult(null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new ToolResult(List.of(), null));
        assertThrows(NullPointerException.class, () -> ToolContent.text(null));
        assertThrows(NullPointerException.class, () -> ToolResult.structured(null));
        assertEquals(List.of(ToolContent.text("human")),
            ToolResult.textAndStructured("human", LiteralValue.of(42)).content());
    }

    @Test
    void resultDefensivelyCopiesOrderedContent() {
        var blocks = new ArrayList<ToolContent>(List.of(ToolContent.text("one"), ToolContent.text("two")));
        var result = new ToolResult(blocks, Optional.empty());
        blocks.clear();
        assertEquals(List.of(ToolContent.text("one"), ToolContent.text("two")), result.content());
        assertThrows(UnsupportedOperationException.class, result.content()::clear);
        assertThrows(NullPointerException.class, () -> new ToolResult(java.util.Arrays.asList((ToolContent) null), Optional.empty()));
    }

    @Test
    void exceptionPreservesStableFailureCodeAndCause() {
        var cause = new IllegalStateException("disk unavailable");
        var exception = new ToolException(ToolFailureCode.IO_ERROR, "cannot read", cause);

        assertEquals(new ToolFailure(ToolFailureCode.IO_ERROR, "cannot read"), exception.failure());
        assertSame(cause, exception.getCause());
        assertEquals(ToolFailureCode.NOT_FOUND,
            new ToolException(ToolFailureCode.NOT_FOUND, "missing").failure().code());
        assertThrows(NullPointerException.class,
            () -> new ToolException(ToolFailureCode.IO_ERROR, null));
    }

    @Test
    void exposesStableContributionAndSpillServiceKeys() {
        assertEquals("fibra.tool", ToolContributions.KIND.name());
        assertEquals(ToolDescriptor.class, ToolContributions.KIND.descriptorType());
        assertEquals(ToolRequest.class, ToolContributions.KIND.inputType());
        assertEquals(ToolResult.class, ToolContributions.KIND.outputType());
        assertEquals(new com.sstlfsj.fibra.bridge.ContributionId("provider", "read"),
            ToolContributions.id("provider", "read"));
        assertEquals("fibra.tool.result-spill-store", ToolServices.RESULT_SPILL_STORE.name());
        assertEquals(ResultSpillStore.class, ToolServices.RESULT_SPILL_STORE.type());
    }

    @Test
    void remoteCodecRoundTripsDescriptorInputAndNullableData() {
        var codec = ToolContributions.KIND.codec().orElseThrow();
        var descriptor = new ToolDescriptor("Remote", "Remote tool", object(Map.of("type", "object")),
            object(Map.of("type", "object")));
        var request = ToolRequest.of(Map.of("path", "note.txt"));
        var result = ToolResult.text("done");

        assertEquals(descriptor, codec.decodeDescriptor(LiteralValue.of(Map.of(
            "displayName", "Remote", "description", "Remote tool",
            "inputSchema", descriptor.inputSchema().toJava(),
            "outputSchema", descriptor.outputSchema().toJava()))));
        assertEquals(request.arguments(), codec.decodeInput(codec.encodeInput(request)).arguments());
        assertEquals(result, codec.decodeOutput(codec.encodeOutput(result)));
    }

    @Test
    void remoteCodecRoundTripsStructuredResult() {
        var codec = ToolContributions.KIND.codec().orElseThrow();
        var result = ToolResult.structured(LiteralValue.of(Map.of("count", 1)));

        assertEquals(2, codec.schemaVersion());
        assertEquals(Map.of("content", List.of(Map.of("type", "text", "text", "{\"count\":1}")),
            "structuredContent", Map.of("count", java.math.BigDecimal.ONE)), codec.encodeOutput(result).toJava());
        assertEquals(result, codec.decodeOutput(codec.encodeOutput(result)));
    }

    @Test
    void remoteCodecPreservesEveryJsonValueAndDistinguishesMissingFromNull() {
        var codec = ToolContributions.KIND.codec().orElseThrow();
        for (var value : java.util.Arrays.asList(null, "text", 42, true, List.of(1, 2), Map.of("key", "value"))) {
            var result = ToolResult.structured(LiteralValue.of(value));
            assertEquals(result, codec.decodeOutput(codec.encodeOutput(result)));
            assertTrue(((Map<?, ?>) codec.encodeOutput(result).toJava()).containsKey("structuredContent"));
        }
        assertFalse(((Map<?, ?>) codec.encodeOutput(ToolResult.text("done")).toJava()).containsKey("structuredContent"));
        assertEquals(new ToolResult(List.of(), Optional.empty()), codec.decodeOutput(
            LiteralValue.of(Map.of("content", List.of()))));
    }

    @Test
    void remoteCodecRejectsMalformedOrUnknownContentBlocks() {
        var codec = ToolContributions.KIND.codec().orElseThrow();
        for (var content : List.of("not-array", List.of("text"), List.of(Map.of("type", "image", "data", "AA==")),
            List.of(Map.of("type", "text")), List.of(Map.of("type", "text", "text", 42)),
            List.of(Map.of("type", "text", "text", "ok", "extra", true)))) {
            assertThrows(IllegalArgumentException.class, () -> codec.decodeOutput(
                LiteralValue.of(Map.of("content", content))));
        }
        assertThrows(IllegalArgumentException.class, () -> codec.decodeOutput(LiteralValue.of(Map.of(
            "content", List.of(), "isError", false))));
    }

    @Test
    void remoteCodecRejectsMissingAndUnknownFields() {
        var codec = ToolContributions.KIND.codec().orElseThrow();

        assertThrows(IllegalArgumentException.class,
            () -> codec.decodeInput(LiteralValue.of(Map.of("arguments", Map.of(), "extra", true))));
        assertThrows(IllegalArgumentException.class,
            () -> codec.decodeOutput(LiteralValue.of(Map.of("text", "done"))));
        assertThrows(IllegalArgumentException.class,
            () -> codec.decodeOutput(LiteralValue.of(Map.of("text", "done", "data", Map.of()))));
        assertThrows(IllegalArgumentException.class,
            () -> codec.decodeDescriptor(LiteralValue.of(Map.of("displayName", "name", "description", "desc",
                "inputSchema", Map.of(), "outputSchema", Map.of(), "extra", true))));
    }

    @Test
    void remoteCodecMapsEveryStableToolFailureAndRejectsInvalidVersions() {
        var codec = ToolContributions.KIND.codec().orElseThrow();
        for (var code : ToolFailureCode.values()) {
            var failure = new RemoteContributionFailure(-32001, "failure", LiteralValue.of(Map.of(
                "kind", "fibra.tool.failure", "schemaVersion", 2, "code", code.name())));
            var mapped = codec.mapRemoteFailure(failure);
            assertTrue(mapped.isPresent());
            assertEquals(code, assertInstanceOf(ToolException.class, mapped.orElseThrow()).failure().code());
        }

        var fractional = new RemoteContributionFailure(-32001, "failure", LiteralValue.of(Map.of(
            "kind", "fibra.tool.failure", "schemaVersion", 1.5, "code", "ABORTED")));
        var tooLarge = new RemoteContributionFailure(-32001, "failure", LiteralValue.of(Map.of(
            "kind", "fibra.tool.failure", "schemaVersion", Long.MAX_VALUE, "code", "ABORTED")));
        assertTrue(codec.mapRemoteFailure(fractional).isEmpty());
        assertTrue(codec.mapRemoteFailure(tooLarge).isEmpty());
        assertTrue(codec.mapRemoteFailure(new RemoteContributionFailure(-32001, "legacy", LiteralValue.of(
            Map.of("kind", "fibra.tool.failure", "schemaVersion", 1, "code", "ABORTED")))).isEmpty());
    }

    private static LiteralValue.ObjectValue object(Map<String, ?> values) {
        return (LiteralValue.ObjectValue) LiteralValue.of(values);
    }
}
