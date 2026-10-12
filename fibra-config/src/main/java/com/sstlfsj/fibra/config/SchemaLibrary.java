package com.sstlfsj.fibra.config;

import com.networknt.schema.AbsoluteIri;
import com.networknt.schema.OutputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.resource.InputStreamSource;
import com.networknt.schema.resource.SchemaLoader;
import com.sstlfsj.fibra.value.LiteralValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 第三方执行器适配；无作者资源检索、TCCL 或第三方错误对象外泄。 */
final class SchemaLibrary {
    private static final Map<String, String> META_RESOURCES = Map.of(
        SchemaProfile.DIALECT, "/draft/2020-12/schema",
        "https://json-schema.org/draft/2020-12/meta/core", "/draft/2020-12/meta/core",
        "https://json-schema.org/draft/2020-12/meta/applicator", "/draft/2020-12/meta/applicator",
        "https://json-schema.org/draft/2020-12/meta/unevaluated", "/draft/2020-12/meta/unevaluated",
        "https://json-schema.org/draft/2020-12/meta/validation", "/draft/2020-12/meta/validation",
        "https://json-schema.org/draft/2020-12/meta/meta-data", "/draft/2020-12/meta/meta-data",
        "https://json-schema.org/draft/2020-12/meta/format-annotation", "/draft/2020-12/meta/format-annotation",
        "https://json-schema.org/draft/2020-12/meta/content", "/draft/2020-12/meta/content");

    private SchemaLibrary() { }

    static Schema compile(ConfigSchemaSource source) {
        try {
            var tree = tree(source.schema());
            var metaRegistry = registry(true);
            var meta = metaRegistry.getSchema(SchemaLocation.of(SchemaProfile.DIALECT));
            if (!meta.validate(tree, OutputFormat.BOOLEAN)) throw SchemaDocumentReader.invalid();
            var schema = registry(false).getSchema(tree);
            schema.initializeValidators();
            return schema;
        } catch (RuntimeException exception) {
            // 即使库的异常含源 schema/value，也不保留 cause、suppressed 或 message。
            throw SchemaDocumentReader.invalid();
        }
    }

    static Optional<ConfigViolation> validate(Schema schema, SchemaProfile.Admission admission, LiteralValue value) {
        try {
            var errors = schema.validate(tree(value));
            if (errors.isEmpty()) return Optional.empty();
            var error = errors.getFirst();
            var keyword = SchemaProfile.ASSERTIONS.contains(error.getKeyword()) ? error.getKeyword() : "";
            return Optional.of(new ConfigViolation("VALUE_MISMATCH", keyword,
                SchemaDiagnostics.pointer(admission, error.getInstanceLocation())));
        } catch (RuntimeException exception) {
            return Optional.of(new ConfigViolation("VALUE_MISMATCH", "", ""));
        }
    }

    private static SchemaRegistry registry(boolean meta) {
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaLoader(new HostResources(meta)));
    }

    private static JsonNode tree(LiteralValue value) {
        return switch (value) {
            case LiteralValue.NullValue ignored -> NullNode.getInstance();
            case LiteralValue.BooleanValue scalar -> BooleanNode.valueOf(scalar.value());
            case LiteralValue.StringValue scalar -> StringNode.valueOf(scalar.value());
            case LiteralValue.NumberValue scalar -> DecimalNode.valueOf(scalar.value());
            case LiteralValue.ListValue list -> {
                var array = new ArrayNode(JsonNodeFactory.instance);
                for (var child : list.values()) array.add(tree(child));
                yield array;
            }
            case LiteralValue.ObjectValue object -> {
                var result = new ObjectNode(JsonNodeFactory.instance);
                object.values().forEach((key, child) -> result.set(key, tree(child)));
                yield result;
            }
        };
    }

    private static final class HostResources extends SchemaLoader {
        private final boolean meta;

        private HostResources(boolean meta) {
            super(List.of(), List.of());
            this.meta = meta;
        }

        @Override
        public InputStreamSource getSchemaResource(AbsoluteIri iri) {
            var path = meta ? META_RESOURCES.get(iri.toString()) : null;
            if (path == null) throw SchemaDocumentReader.invalid();
            return () -> {
                var stream = SchemaRegistry.class.getResourceAsStream(path);
                if (stream == null) throw SchemaDocumentReader.invalid();
                return stream;
            };
        }
    }
}
