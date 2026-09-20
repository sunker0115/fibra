package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** 一次配置求值使用的不可变宿主上下文。 */
public final class ConfigContextSnapshot {
    private static final ConfigContextSnapshot EMPTY = of(new LiteralValue.ObjectValue(Map.of()));

    private final LiteralValue.ObjectValue values;
    private final String revision;

    private ConfigContextSnapshot(LiteralValue.ObjectValue values) {
        if (values.values().containsKey("entry")) {
            throw new IllegalArgumentException("top-level context key 'entry' is reserved");
        }
        this.values = values;
        revision = revision(values);
    }

    public static ConfigContextSnapshot empty() {
        return EMPTY;
    }

    public static ConfigContextSnapshot of(LiteralValue.ObjectValue values) {
        return new ConfigContextSnapshot(Objects.requireNonNull(values, "values"));
    }

    public LiteralValue.ObjectValue values() {
        return values;
    }

    public String revision() {
        return revision;
    }

    @Override
    public boolean equals(Object candidate) {
        return this == candidate || candidate instanceof ConfigContextSnapshot other
            && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    private static String revision(LiteralValue.ObjectValue values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                values.canonicalJson().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
