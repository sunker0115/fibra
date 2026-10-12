package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** 严格 UTF-8/token 解码；在递归容器创建前限制深度与节点数。 */
final class SchemaDocumentReader {
    private static final JsonFactory JSON = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(68)
            .maxNumberLength(512).maxStringLength(SchemaBudget.MIB).build()).build();

    private int nodes;

    LiteralValue read(byte[] bytes) {
        if (bytes.length > SchemaBudget.MIB) throw SchemaBudget.exceeded();
        try {
            var text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            try (var parser = JSON.createParser(ObjectReadContext.empty(), text)) {
                parser.nextToken();
                var value = value(parser, 1);
                if (parser.nextToken() != null) throw invalid();
                return value;
            }
        } catch (ConfigSchemaException exception) {
            throw exception;
        } catch (StreamConstraintsException exception) {
            throw SchemaBudget.exceeded();
        } catch (CharacterCodingException | RuntimeException exception) {
            throw invalid();
        }
    }

    private LiteralValue value(JsonParser parser, int depth) {
        if (depth > 68 || ++nodes > 32768) throw SchemaBudget.exceeded();
        var token = parser.currentToken();
        if (token == null) throw invalid();
        return switch (token) {
            case START_OBJECT -> {
                var values = new LinkedHashMap<String, LiteralValue>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw invalid();
                    var name = parser.currentName();
                    parser.nextToken();
                    values.put(name, value(parser, depth + 1));
                }
                yield new LiteralValue.ObjectValue(values);
            }
            case START_ARRAY -> {
                var values = new ArrayList<LiteralValue>();
                while (parser.nextToken() != JsonToken.END_ARRAY) values.add(value(parser, depth + 1));
                yield new LiteralValue.ListValue(values);
            }
            case VALUE_STRING -> new LiteralValue.StringValue(parser.getString());
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                var text = parser.getString();
                if (text.length() > 512) throw SchemaBudget.exceeded();
                yield new LiteralValue.NumberValue(SchemaBudget.number(new BigDecimal(text)));
            }
            case VALUE_TRUE -> new LiteralValue.BooleanValue(true);
            case VALUE_FALSE -> new LiteralValue.BooleanValue(false);
            case VALUE_NULL -> LiteralValue.NullValue.INSTANCE;
            default -> throw invalid();
        };
    }

    static ConfigSchemaException invalid() {
        return new ConfigSchemaException(ConfigSchemaException.Code.INVALID_SCHEMA);
    }
}
