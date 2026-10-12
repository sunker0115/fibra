package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;

import java.math.BigDecimal;
import java.util.ArrayDeque;

/** 在 canonical/库对象分配前计量原始 literal；接纳分数不是 CPU 或堆内存硬界。 */
final class SchemaBudget {
    static final int MIB = 1_048_576;
    static final int ENTRY_WORK = 1_000_000;
    static final int BATCH_WORK = 4_000_000;
    static final int EXPANDED = 8192;

    private SchemaBudget() { }

    static Stats source(LiteralValue value) {
        return measure(value, MIB, 32768, 64, MIB);
    }

    static Stats schema(LiteralValue value) {
        return measure(value, 65536, 4096, 32, 65536);
    }

    static Stats effective(LiteralValue value) {
        return measure(value, MIB, 10000, 64, 65536);
    }

    static BigDecimal number(BigDecimal number) {
        var normalized = number.signum() == 0 ? BigDecimal.ZERO : number.stripTrailingZeros();
        long scale = normalized.scale();
        long precision = normalized.precision();
        long exponent = precision - scale - 1;
        if (precision > 256 || Math.abs(scale) > 256 || exponent < -256 || exponent > 256) {
            throw exceeded();
        }
        return normalized;
    }

    static Stats measure(LiteralValue value, int maxBytes, int maxNodes, int maxDepth, int maxString) {
        var pending = new ArrayDeque<AtDepth>();
        pending.push(new AtDepth(value, 1));
        long bytes = 0;
        long text = 0;
        int nodes = 0;
        while (!pending.isEmpty()) {
            var next = pending.pop();
            if (++nodes > maxNodes || next.depth() > maxDepth) throw exceeded();
            switch (next.value()) {
                case LiteralValue.NullValue ignored -> bytes += 4;
                case LiteralValue.BooleanValue scalar -> bytes += scalar.value() ? 4 : 5;
                case LiteralValue.NumberValue scalar -> bytes += number(scalar.value()).toString().length();
                case LiteralValue.StringValue scalar -> {
                    if (utf8Length(scalar.value(), maxString) > maxString) throw exceeded();
                    bytes += quotedBytes(scalar.value(), maxBytes);
                    text += scalar.value().length();
                }
                case LiteralValue.ListValue list -> {
                    if ((long) nodes + pending.size() + list.values().size() > maxNodes) throw exceeded();
                    bytes += 2L + Math.max(0, list.values().size() - 1);
                    for (var child : list.values()) pending.push(new AtDepth(child, next.depth() + 1));
                }
                case LiteralValue.ObjectValue object -> {
                    if ((long) nodes + pending.size() + object.values().size() > maxNodes) throw exceeded();
                    bytes += 2L + Math.max(0, object.values().size() - 1);
                    for (var entry : object.values().entrySet()) {
                        if (utf8Length(entry.getKey(), maxString) > maxString) throw exceeded();
                        bytes += quotedBytes(entry.getKey(), maxBytes) + 1;
                        if (bytes > maxBytes) throw exceeded();
                        text += entry.getKey().length();
                        pending.push(new AtDepth(entry.getValue(), next.depth() + 1));
                    }
                }
            }
            if (bytes > maxBytes) throw exceeded();
        }
        return new Stats((int) bytes, nodes, text);
    }

    static long add(long left, long right, long limit) {
        return left > limit || right > limit || left > limit - right ? limit + 1 : left + right;
    }

    static long multiply(long left, long right, long limit) {
        return left != 0 && right > limit / left ? limit + 1 : left * right;
    }

    static long units(long length) {
        return (length + 255) / 256;
    }

    static long work(long expanded, int combinations, Stats value) {
        return multiply(multiply(expanded, value.nodes() + units(value.text()), ENTRY_WORK),
            combinations + 1L, ENTRY_WORK);
    }

    static long utf8Length(String text, int limit) {
        long bytes = 0;
        for (var i = 0; i < text.length() && bytes <= limit; i++) {
            char c = text.charAt(i);
            if (c < 0x80) bytes++;
            else if (c < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < text.length()
                && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else bytes += Character.isSurrogate(c) ? 1 : 3;
        }
        return bytes;
    }

    // 必须与 LiteralValue.canonicalJson 的转义保持一致，包括每个 surrogate 的六字节编码。
    private static long quotedBytes(String text, int limit) {
        long bytes = 2;
        for (var i = 0; i < text.length() && bytes <= limit; i++) {
            char c = text.charAt(i);
            bytes += switch (c) {
                case '"', '\\', '\n', '\r', '\t', '\b', '\f' -> 2;
                default -> c < 0x20 || Character.isSurrogate(c) ? 6 : c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
            };
        }
        return bytes;
    }

    static ConfigSchemaException exceeded() {
        return new ConfigSchemaException(ConfigSchemaException.Code.BUDGET_EXCEEDED);
    }

    record Stats(int bytes, int nodes, long text) { }
    private record AtDepth(LiteralValue value, int depth) { }
}
