package com.sstlfsj.fibra.event;

import java.util.Objects;

public record EventKey<L>(String name, Class<L> listenerType, EventMode mode) {
    public EventKey {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("event name must not be blank");
        }
        Objects.requireNonNull(listenerType, "listenerType");
        Objects.requireNonNull(mode, "mode");
        if (!listenerType.isInterface()) {
            throw new IllegalArgumentException("event listener type must be an interface");
        }
    }

    public static <L> EventKey<L> of(String name, Class<L> listenerType,
                                     EventMode mode) {
        return new EventKey<>(name, listenerType, mode);
    }
}
