package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 固定格式配置合同文档的无 I/O 解码入口。 */
public final class DefinitionConfigDocument {
    private final String profile;
    private final Map<String, Map<String, ConfigSchemaSource>> facets;

    private DefinitionConfigDocument(String profile, Map<String, Map<String, ConfigSchemaSource>> facets) {
        this.profile = profile;
        this.facets = Collections.unmodifiableMap(facets);
    }

    public static DefinitionConfigDocument parse(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        var root = object(new SchemaDocumentReader().read(bytes));
        if (!root.keySet().equals(Set.of("format", "profile", "facets"))
            || !(root.get("format") instanceof LiteralValue.NumberValue format)
            || format.value().compareTo(BigDecimal.ONE) != 0
            || !(root.get("profile") instanceof LiteralValue.StringValue profile)) {
            throw SchemaDocumentReader.invalid();
        }
        if (!profile.value().equals("fibra-config-1")) {
            throw new ConfigSchemaException(ConfigSchemaException.Code.UNSUPPORTED_PROFILE);
        }
        var facets = new LinkedHashMap<String, Map<String, ConfigSchemaSource>>();
        var count = 0;
        for (var facet : object(root.get("facets")).entrySet()) {
            var definitions = new LinkedHashMap<String, ConfigSchemaSource>();
            for (var definition : object(facet.getValue()).entrySet()) {
                if (++count > 128) throw SchemaBudget.exceeded();
                if (!(definition.getValue() instanceof LiteralValue.ObjectValue schema)) {
                    throw SchemaDocumentReader.invalid();
                }
                definitions.put(definition.getKey(), new ConfigSchemaSource(profile.value(), schema));
            }
            facets.put(facet.getKey(), Collections.unmodifiableMap(definitions));
        }
        return new DefinitionConfigDocument(profile.value(), facets);
    }

    public String profile() {
        return profile;
    }

    public Map<String, Map<String, ConfigSchemaSource>> facets() {
        return facets;
    }

    private static Map<String, LiteralValue> object(LiteralValue value) {
        if (value instanceof LiteralValue.ObjectValue object) return object.values();
        throw SchemaDocumentReader.invalid();
    }
}
