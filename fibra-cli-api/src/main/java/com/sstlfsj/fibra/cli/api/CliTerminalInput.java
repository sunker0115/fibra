package com.sstlfsj.fibra.cli.api;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** renderer 收到的一个按键或完整 bracketed-paste 事件。 */
public final class CliTerminalInput {
    private final CliTerminalKey key;
    private final String text;
    private final Set<CliTerminalModifier> modifiers;
    private final boolean paste;

    private CliTerminalInput(CliTerminalKey key, String text,
                             Set<CliTerminalModifier> modifiers, boolean paste) {
        this.key = key;
        this.text = Objects.requireNonNull(text, "text");
        this.modifiers = Set.copyOf(Objects.requireNonNull(modifiers, "modifiers"));
        this.paste = paste;
    }

    public static CliTerminalInput key(CliTerminalKey key, String text,
                                       Set<CliTerminalModifier> modifiers) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(modifiers, "modifiers");
        if (key == CliTerminalKey.CHARACTER && text.isEmpty()) {
            throw new IllegalArgumentException("character input must carry text");
        }
        if (key != CliTerminalKey.CHARACTER && !text.isEmpty()) {
            throw new IllegalArgumentException("named terminal keys must not carry text");
        }
        return new CliTerminalInput(key, text, modifiers, false);
    }

    public static CliTerminalInput paste(String text) {
        return new CliTerminalInput(null, text, Set.of(), true);
    }

    public boolean isPaste() {
        return paste;
    }

    public Optional<CliTerminalKey> key() {
        return Optional.ofNullable(key);
    }

    public String text() {
        return text;
    }

    public Set<CliTerminalModifier> modifiers() {
        return modifiers;
    }

    @Override public boolean equals(Object candidate) {
        if (this == candidate) return true;
        if (!(candidate instanceof CliTerminalInput other)) return false;
        return paste == other.paste && Objects.equals(key, other.key)
            && text.equals(other.text) && modifiers.equals(other.modifiers);
    }

    @Override public int hashCode() {
        return Objects.hash(key, text, modifiers, paste);
    }

    @Override public String toString() {
        return paste ? "CliTerminalInput[paste=" + text + "]"
            : "CliTerminalInput[key=" + key + ", text=" + text
                + ", modifiers=" + modifiers + "]";
    }
}
