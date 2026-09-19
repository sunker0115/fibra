package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.value.LiteralValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class HostCapabilitySnapshot {
    private static final HostCapabilitySnapshot EMPTY = of(Map.of());

    private final LiteralValue.ObjectValue values;
    private final String fingerprint;

    private HostCapabilitySnapshot(LiteralValue.ObjectValue values) {
        this.values = Objects.requireNonNull(values, "values");
        fingerprint = digest(values.canonicalJson());
    }

    public static HostCapabilitySnapshot empty() {
        return EMPTY;
    }

    public static HostCapabilitySnapshot of(Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        return new HostCapabilitySnapshot(
            (LiteralValue.ObjectValue) LiteralValue.of(values));
    }

    public LiteralValue.ObjectValue values() {
        return values;
    }

    /** Key presence means the capability is available; values are immutable descriptors. */
    public Set<String> availableNames() {
        return values.values().keySet();
    }

    public String fingerprint() {
        return fingerprint;
    }

    @Override
    public boolean equals(Object candidate) {
        return this == candidate
            || candidate instanceof HostCapabilitySnapshot other
            && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
