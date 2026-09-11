package com.sstlfsj.fibra.plugins.storage.json;

import com.sstlfsj.fibra.plugins.storage.ConfigDocument;
import com.sstlfsj.fibra.plugins.storage.StorageErrorCode;
import com.sstlfsj.fibra.plugins.storage.StorageException;
import com.sstlfsj.fibra.value.LiteralValue;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class JsonDocumentCodec {
    private static final long FORMAT_VERSION = 1;
    private static final Set<String> FIELDS = Set.of("formatVersion", "revision", "values");

    private JsonDocumentCodec() {
    }

    static byte[] encode(ConfigDocument document) {
        var envelope = LiteralValue.of(Map.of(
            "formatVersion", FORMAT_VERSION,
            "revision", document.revision(),
            "values", document.values()));
        return (envelope.canonicalJson() + "\n").getBytes(StandardCharsets.UTF_8);
    }

    static ConfigDocument decode(byte[] content) {
        final LiteralValue parsed;
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            parsed = new Parser(decoder.decode(ByteBuffer.wrap(content)).toString()).parse();
        } catch (CharacterCodingException | RuntimeException failure) {
            throw malformed("configuration document is not valid JSON", failure);
        }
        if (!(parsed instanceof LiteralValue.ObjectValue envelope)
            || !envelope.values().keySet().equals(FIELDS)) {
            throw malformed("configuration document has an invalid envelope", null);
        }
        var version = integer(envelope.values().get("formatVersion"), "formatVersion");
        if (version != FORMAT_VERSION) {
            throw new StorageException(StorageErrorCode.VERSION_MISMATCH,
                "stored format version " + version + " is not supported");
        }
        var revision = integer(envelope.values().get("revision"), "revision");
        if (revision < 0) {
            throw malformed("revision must not be negative", null);
        }
        if (!(envelope.values().get("values") instanceof LiteralValue.ObjectValue values)) {
            throw malformed("values must be a JSON object", null);
        }
        try {
            return new ConfigDocument(revision, values.values());
        } catch (IllegalArgumentException failure) {
            throw malformed("configuration values are invalid", failure);
        }
    }

    private static long integer(LiteralValue value, String field) {
        if (!(value instanceof LiteralValue.NumberValue number)) {
            throw malformed(field + " must be an integer", null);
        }
        try {
            return number.value().longValueExact();
        } catch (ArithmeticException failure) {
            throw malformed(field + " must be an integer in the signed 64-bit range", failure);
        }
    }

    private static StorageException malformed(String message, Throwable cause) {
        return new StorageException(StorageErrorCode.MALFORMED_DOCUMENT, message, cause);
    }

    private static final class Parser {
        private final String input;
        private int position;

        private Parser(String input) {
            this.input = input;
        }

        private LiteralValue parse() {
            skipWhitespace();
            var value = value();
            skipWhitespace();
            if (position != input.length()) {
                throw error("trailing content");
            }
            return value;
        }

        private LiteralValue value() {
            if (position >= input.length()) throw error("unexpected end of input");
            return switch (input.charAt(position)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> new LiteralValue.StringValue(string());
                case 't' -> literal("true", new LiteralValue.BooleanValue(true));
                case 'f' -> literal("false", new LiteralValue.BooleanValue(false));
                case 'n' -> literal("null", LiteralValue.NullValue.INSTANCE);
                default -> number();
            };
        }

        private LiteralValue object() {
            position++;
            skipWhitespace();
            var values = new LinkedHashMap<String, LiteralValue>();
            if (take('}')) return new LiteralValue.ObjectValue(values);
            while (true) {
                if (position >= input.length() || input.charAt(position) != '"') {
                    throw error("object key must be a string");
                }
                var key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                if (values.putIfAbsent(key, value()) != null) {
                    throw error("duplicate object key");
                }
                skipWhitespace();
                if (take('}')) return new LiteralValue.ObjectValue(values);
                expect(',');
                skipWhitespace();
            }
        }

        private LiteralValue array() {
            position++;
            skipWhitespace();
            var values = new ArrayList<LiteralValue>();
            if (take(']')) return new LiteralValue.ListValue(values);
            while (true) {
                values.add(value());
                skipWhitespace();
                if (take(']')) return new LiteralValue.ListValue(values);
                expect(',');
                skipWhitespace();
            }
        }

        private String string() {
            expect('"');
            var value = new StringBuilder();
            while (position < input.length()) {
                var character = input.charAt(position++);
                if (character == '"') return value.toString();
                if (character < 0x20) throw error("unescaped control character");
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                if (position >= input.length()) throw error("unfinished escape");
                switch (input.charAt(position++)) {
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case '/' -> value.append('/');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> value.append(unicode());
                    default -> throw error("invalid escape");
                }
            }
            throw error("unterminated string");
        }

        private char unicode() {
            if (position + 4 > input.length()) throw error("unfinished unicode escape");
            var value = 0;
            for (var index = 0; index < 4; index++) {
                var digit = Character.digit(input.charAt(position++), 16);
                if (digit < 0) throw error("invalid unicode escape");
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private LiteralValue number() {
            var start = position;
            take('-');
            if (take('0')) {
                if (position < input.length() && Character.isDigit(input.charAt(position))) {
                    throw error("leading zero in number");
                }
            } else {
                digits();
            }
            if (take('.')) digits();
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                digits();
            }
            if (position == start) throw error("invalid value");
            try {
                return new LiteralValue.NumberValue(
                    new BigDecimal(input.substring(start, position)));
            } catch (NumberFormatException failure) {
                throw error("invalid number");
            }
        }

        private void digits() {
            var start = position;
            while (position < input.length() && Character.isDigit(input.charAt(position))) {
                position++;
            }
            if (position == start) throw error("expected digit");
        }

        private <T extends LiteralValue> T literal(String text, T value) {
            if (!input.startsWith(text, position)) throw error("invalid literal");
            position += text.length();
            return value;
        }

        private void skipWhitespace() {
            while (position < input.length()) {
                var character = input.charAt(position);
                if (character != ' ' && character != '\n' && character != '\r'
                    && character != '\t') return;
                position++;
            }
        }

        private boolean take(char expected) {
            if (position < input.length() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) throw error("expected '" + expected + "'");
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at offset " + position);
        }
    }
}
