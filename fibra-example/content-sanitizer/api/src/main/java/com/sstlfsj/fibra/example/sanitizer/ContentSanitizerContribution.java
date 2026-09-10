package com.sstlfsj.fibra.example.sanitizer;

import com.sstlfsj.fibra.bridge.ContributionCodec;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ContentSanitizerContribution {
    public static final String KIND_NAME = "content-sanitizer";
    public static final String LOCAL_NAME = "sanitize";
    public static final ContributionKind<SanitizerDescriptor, SanitizeRequest,
        SanitizeResult> KIND = ContributionKind.remote(KIND_NAME,
            SanitizerDescriptor.class, SanitizeRequest.class, SanitizeResult.class,
            new Codec());

    private ContentSanitizerContribution() {
    }

    public static ContributionId id(String providerInstanceId) {
        return new ContributionId(providerInstanceId, LOCAL_NAME);
    }

    private static final class Codec implements ContributionCodec<SanitizerDescriptor,
        SanitizeRequest, SanitizeResult> {
        @Override
        public int schemaVersion() {
            return 1;
        }

        @Override
        public SanitizerDescriptor decodeDescriptor(Object descriptor) {
            var values = object(descriptor, "descriptor");
            return new SanitizerDescriptor(text(values, "title"),
                stringList(values, "supportedRules"));
        }

        @Override
        public Object encodeInput(SanitizeRequest input) {
            return Map.of("text", input.text());
        }

        @Override
        public SanitizeRequest decodeInput(Object input) {
            return new SanitizeRequest(text(object(input, "input"), "text"));
        }

        @Override
        public Object encodeOutput(SanitizeResult output) {
            return Map.of("text", output.text(), "redactions", output.redactions(),
                "total", output.total());
        }

        @Override
        public SanitizeResult decodeOutput(Object output) {
            var values = object(output, "output");
            return new SanitizeResult(text(values, "text"),
                counts(values.get("redactions")), integer(values, "total"));
        }
    }

    private static Map<?, ?> object(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return map;
    }

    private static String text(Map<?, ?> values, String name) {
        var value = values.get(name);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(name + " must be text");
        }
        return text;
    }

    private static int integer(Map<?, ?> values, String name) {
        var value = values.get(name);
        if (!(value instanceof Number number) || number.intValue() < 0
            || number.doubleValue() != number.intValue()) {
            throw new IllegalArgumentException(name + " must be a non-negative integer");
        }
        return number.intValue();
    }

    private static List<String> stringList(Map<?, ?> values, String name) {
        var value = values.get(name);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        var result = new ArrayList<String>();
        for (var item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(name + " must contain names");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static Map<String, Integer> counts(Object value) {
        var map = object(value, "redactions");
        var result = new LinkedHashMap<String, Integer>();
        map.forEach((key, count) -> {
            if (!(key instanceof String name) || name.isBlank()
                || !(count instanceof Number number) || number.intValue() < 0
                || number.doubleValue() != number.intValue()) {
                throw new IllegalArgumentException(
                    "redactions must contain non-negative counts");
            }
            result.put(name, number.intValue());
        });
        return Map.copyOf(result);
    }
}
