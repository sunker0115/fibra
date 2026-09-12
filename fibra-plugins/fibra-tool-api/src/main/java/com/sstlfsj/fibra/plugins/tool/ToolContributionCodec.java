package com.sstlfsj.fibra.plugins.tool;

import com.sstlfsj.fibra.CancellationToken;
import com.sstlfsj.fibra.bridge.ContributionCodec;
import com.sstlfsj.fibra.bridge.RemoteContributionFailure;
import com.sstlfsj.fibra.value.LiteralValue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class ToolContributionCodec
    implements ContributionCodec<ToolDescriptor, ToolRequest, ToolResult> {
    private static final int SCHEMA_VERSION = 2;
    private static final Set<String> DESCRIPTOR_FIELDS = Set.of(
        "displayName", "description", "inputSchema", "outputSchema");
    private static final Set<String> INPUT_FIELDS = Set.of("arguments");
    private static final Set<String> OUTPUT_FIELDS = Set.of("content");
    private static final Set<String> STRUCTURED_OUTPUT_FIELDS = Set.of("content", "structuredContent");
    private static final Set<String> TEXT_FIELDS = Set.of("type", "text");
    private static final Set<String> FAILURE_FIELDS = Set.of("kind", "schemaVersion", "code");

    @Override
    public int schemaVersion() {
        return SCHEMA_VERSION;
    }

    @Override
    public ToolDescriptor decodeDescriptor(Object value) {
        var fields = object(value, "descriptor", DESCRIPTOR_FIELDS);
        return new ToolDescriptor(text(fields, "displayName"), text(fields, "description"),
            objectLiteral(fields, "inputSchema"), objectLiteral(fields, "outputSchema"));
    }

    @Override
    public Object encodeInput(ToolRequest input) {
        Objects.requireNonNull(input, "input");
        return Map.of("arguments", input.arguments().toJava());
    }

    @Override
    public ToolRequest decodeInput(Object value) {
        var fields = object(value, "input", INPUT_FIELDS);
        return new ToolRequest(objectLiteral(fields, "arguments"), CancellationToken.never());
    }

    @Override
    public Object encodeOutput(ToolResult output) {
        Objects.requireNonNull(output, "output");
        var fields = new LinkedHashMap<String, Object>();
        fields.put("content", output.content().stream().map(content -> switch (content) {
            case ToolContent.Text text -> Map.of("type", "text", "text", text.text());
        }).toList());
        output.structuredContent().ifPresent(value -> fields.put("structuredContent", value.toJava()));
        return fields;
    }

    @Override
    public ToolResult decodeOutput(Object value) {
        var fields = object(value, "output", value instanceof Map<?, ?> raw && raw.containsKey("structuredContent")
            ? STRUCTURED_OUTPUT_FIELDS : OUTPUT_FIELDS);
        if (!(fields.get("content") instanceof List<?> blocks)) {
            throw new IllegalArgumentException("invalid tool content");
        }
        var content = new ArrayList<ToolContent>();
        for (var block : blocks) {
            var entry = object(block, "content block", TEXT_FIELDS);
            if (!"text".equals(entry.get("type"))) throw new IllegalArgumentException("unsupported tool content type");
            content.add(ToolContent.text(text(entry, "text")));
        }
        return new ToolResult(content, fields.containsKey("structuredContent")
            ? Optional.of(LiteralValue.of(fields.get("structuredContent"))) : Optional.empty());
    }

    @Override
    public CancellationToken cancellationToken(ToolRequest input) {
        return Objects.requireNonNull(input, "input").cancellation();
    }

    @Override
    public RuntimeException cancellationException() {
        return new ToolException(ToolFailureCode.ABORTED, "tool invocation cancelled");
    }

    @Override
    public Optional<RuntimeException> mapRemoteFailure(RemoteContributionFailure failure) {
        Objects.requireNonNull(failure, "failure");
        var fields = objectOrNull(failure.data().toJava(), FAILURE_FIELDS);
        if (fields == null || !"fibra.tool.failure".equals(fields.get("kind"))
            || !isSchemaVersion(fields.get("schemaVersion"))
            || !(fields.get("code") instanceof String code)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ToolException(ToolFailureCode.valueOf(code), failure.message()));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static Map<String, Object> object(Object value, String label, Set<String> expected) {
        var fields = objectOrNull(value, expected);
        if (fields == null) {
            throw new IllegalArgumentException("invalid tool " + label + " fields");
        }
        return fields;
    }

    private static Map<String, Object> objectOrNull(Object value, Set<String> expected) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        var fields = new LinkedHashMap<String, Object>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key) || fields.put(key, entry.getValue()) != null) {
                return null;
            }
        }
        return fields.keySet().equals(expected) ? fields : null;
    }

    private static String text(Map<String, Object> fields, String field) {
        if (fields.get(field) instanceof String value) {
            return value;
        }
        throw new IllegalArgumentException("invalid tool " + field);
    }

    private static LiteralValue.ObjectValue objectLiteral(Map<String, Object> fields, String field) {
        var literal = LiteralValue.of(fields.get(field));
        if (literal instanceof LiteralValue.ObjectValue object) {
            return object;
        }
        throw new IllegalArgumentException("invalid tool " + field);
    }

    private static boolean isSchemaVersion(Object value) {
        return value instanceof BigDecimal version
            && version.compareTo(BigDecimal.valueOf(SCHEMA_VERSION)) == 0;
    }
}
