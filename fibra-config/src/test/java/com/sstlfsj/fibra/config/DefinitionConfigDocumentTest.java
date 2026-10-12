package com.sstlfsj.fibra.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DefinitionConfigDocumentTest {
    static DefinitionConfigDocument parse(String value) {
        return DefinitionConfigDocument.parse(value.getBytes(StandardCharsets.UTF_8));
    }

    static String document(String schema) {
        return "{\"format\":1,\"profile\":\"fibra-config-1\",\"facets\":{\"main\":{\"sample\":" + schema + "}}}";
    }

    @Test
    void decodesStrictEnvelopeAndDefersDormantSchemaSemantics() {
        var document = parse("{\"format\":1,\"profile\":\"fibra-config-1\",\"facets\":{\"empty\":{},\"main\":{"
            + "\"good\":{\"$schema\":\"" + ConfigSchemaTest.DIALECT + "\",\"type\":\"null\"},"
            + "\"dormant\":{\"misspelt\":true}}}}");
        assertEquals("fibra-config-1", document.profile());
        assertTrue(document.facets().get("empty").isEmpty());
        assertDoesNotThrow(() -> ConfigSchema.compile(document.facets().get("main").get("good")));
        assertThrows(ConfigSchemaException.class, () -> ConfigSchema.compile(document.facets().get("main").get("dormant")));
        assertThrows(UnsupportedOperationException.class, () -> document.facets().clear());
        assertThrows(UnsupportedOperationException.class, () -> document.facets().get("main").clear());
    }

    @Test
    void rejectsMalformedJsonEnvelopeUtf8AndUnsupportedProfileWithoutOriginalContent() {
        for (var text : new String[]{"SECRET", "{}", document("false"), document("{}") + "{}",
            document("{}").replace("\"format\":1", "\"format\":1,\"format\":1"),
            document("{}").replace("\"format\":1", "\"format\":2"),
            document("{}").replace("\"format\":1", "\"format\":\"1\""),
            document("{}").replace("\"facets\":", "\"SECRET\":true,\"facets\":"),
            document("{}").replace("fibra-config-1", "SECRET"),
            document("{\"SECRET\":1,\"SECRET\":2}")}) {
            var error = assertThrows(ConfigSchemaException.class, () -> parse(text));
            assertNull(error.getCause());
            assertEquals(0, error.getSuppressed().length);
            assertFalse(error.toString().contains("SECRET"));
        }
        assertThrows(ConfigSchemaException.class, () -> DefinitionConfigDocument.parse(new byte[]{(byte) 0xc0, (byte) 0xaf}));
    }

    @Test
    void rejectsGlobalBoundsButDefersSingleSchemaQualification() {
        var large = parse(document("{\"description\":\"" + "x".repeat(70_000) + "\"}"));
        assertEquals(ConfigSchemaException.Code.BUDGET_EXCEEDED,
            assertThrows(ConfigSchemaException.class, () -> ConfigSchema.compile(large.facets().get("main").get("sample"))).code());
        assertThrows(ConfigSchemaException.class, () -> parse(document("{\"x\":\"" + "x".repeat(1_048_576) + "\"}")));
        assertThrows(ConfigSchemaException.class, () -> parse(document("{\"x\":" + "[".repeat(70) + "0" + "]".repeat(70) + "}")));
        assertThrows(ConfigSchemaException.class, () -> parse(document("{\"x\":[" + "0,".repeat(32768) + "0]}")));
        assertThrows(ConfigSchemaException.class, () -> parse(document("{\"x\":1e999999999}")));
        assertThrows(ConfigSchemaException.class, () -> parse(document("{\"x\":" + "1".repeat(513) + "}")));
    }
}
