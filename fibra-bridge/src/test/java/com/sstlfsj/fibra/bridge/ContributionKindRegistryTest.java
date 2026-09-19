package com.sstlfsj.fibra.bridge;

import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContributionKindRegistryTest {
    private static final ContributionCodec<String, String, String> CODEC =
        new ContributionCodec<>() {
            @Override public int schemaVersion() { return 1; }
            @Override public String decodeDescriptor(LiteralValue descriptor) {
                return (String) descriptor.toJava();
            }
            @Override public LiteralValue encodeInput(String input) {
                return LiteralValue.of(input);
            }
            @Override public String decodeInput(LiteralValue input) {
                return (String) input.toJava();
            }
            @Override public LiteralValue encodeOutput(String output) {
                return LiteralValue.of(output);
            }
            @Override public String decodeOutput(LiteralValue output) {
                return (String) output.toJava();
            }
        };

    @Test
    void duplicateKindNamesAreRejectedEvenWhenTheirJavaTypesMatch() {
        var first = ContributionKind.local("command", String.class, String.class, String.class);
        var second = ContributionKind.local("command", String.class, String.class, String.class);

        assertThrows(IllegalArgumentException.class,
            () -> ContributionKindRegistry.of(first, second));
    }

    @Test
    void resolutionReturnsTheRegisteredInstanceAndRejectsUnknownKinds() {
        var kind = ContributionKind.remote("command", String.class, String.class,
            String.class, CODEC);
        var registry = ContributionKindRegistry.of(kind);

        assertSame(kind, registry.find("command").orElseThrow());
        assertTrue(registry.find("unknown").isEmpty());
    }

    @Test
    void localKindsAreValidButCannotBeResolvedForRemoteUse() {
        var local = ContributionKind.local("local", String.class, String.class, String.class);
        var remote = ContributionKind.remote("remote", String.class, String.class,
            String.class, CODEC);
        var registry = ContributionKindRegistry.of(List.of(local, remote));

        assertSame(local, registry.find("local").orElseThrow());
        assertTrue(registry.find("local").orElseThrow().codec().isEmpty());
        assertSame(remote, registry.find("remote").orElseThrow());
        assertSame(CODEC, registry.find("remote").orElseThrow().codec().orElseThrow());
        assertTrue(registry.find("unknown").isEmpty());
    }
}
