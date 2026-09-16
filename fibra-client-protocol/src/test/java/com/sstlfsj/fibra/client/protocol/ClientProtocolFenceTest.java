package com.sstlfsj.fibra.client.protocol;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientProtocolFenceTest {
    @Test
    void codecOwnsNoMutableLifecyclePendingState() {
        assertTrue(java.util.Arrays.stream(ClientProtocolCodec.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .allMatch(field -> Modifier.isFinal(field.getModifiers())));
        assertFalse(java.util.Arrays.stream(ClientProtocolCodec.class.getDeclaredClasses())
            .anyMatch(type -> type.getSimpleName().equals("LifecycleFenceTracker")));
    }
}
