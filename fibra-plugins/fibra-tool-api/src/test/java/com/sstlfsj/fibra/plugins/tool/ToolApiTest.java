package com.sstlfsj.fibra.plugins.tool;

import com.sstlfsj.fibra.CancellationSource;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    void resultFactoriesKeepTextAndStructuredDataNonNull() {
        var text = ToolResult.text("done");
        var structured = ToolResult.structured(LiteralValue.of(Map.of("count", 1)));

        assertEquals("done", text.text());
        assertEquals(LiteralValue.of(null), text.data());
        assertEquals("", structured.text());
        assertEquals(LiteralValue.of(Map.of("count", 1)), structured.data());
        assertThrows(NullPointerException.class, () -> new ToolResult(null, LiteralValue.of(null)));
    }

    @Test
    void exceptionPreservesStableFailureCodeAndCause() {
        var cause = new IllegalStateException("disk unavailable");
        var exception = new ToolException(ToolFailureCode.IO_ERROR, "cannot read", cause);

        assertEquals(ToolFailureCode.IO_ERROR, exception.code());
        assertSame(cause, exception.getCause());
        assertEquals(ToolFailureCode.NOT_FOUND,
            new ToolException(ToolFailureCode.NOT_FOUND, "missing").code());
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

    private static LiteralValue.ObjectValue object(Map<String, ?> values) {
        return (LiteralValue.ObjectValue) LiteralValue.of(values);
    }
}
