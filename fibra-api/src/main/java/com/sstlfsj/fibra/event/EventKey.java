package com.sstlfsj.fibra.event;

import java.util.Objects;

public record EventKey<L>(String name, Class<L> listenerType) {
    public EventKey {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("event name must not be blank");
        }
        Objects.requireNonNull(listenerType, "listenerType");
        if (!listenerType.isInterface()) {
            throw new IllegalArgumentException("event listener type must be an interface");
        }
    }

    public static <L> EventKey<L> of(String name, Class<L> listenerType) {
        return new EventKey<>(name, listenerType);
    }
}
