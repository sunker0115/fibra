package com.sstlfsj.fibra.client.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientProtocolFenceTest {
    @Test
    void rejectsLateLifecycleAcknowledgementAcrossAToBToA() {
        var session = new SessionFence("host-1", "client-1");
        var firstA = new LifecycleFence(session, 7, "runtime-a", "operation-a-first");
        var b = new LifecycleFence(session, 8, "runtime-b", "operation-b");
        var secondA = new LifecycleFence(session, 9, "runtime-a", "operation-a-second");
        var tracker = new ClientProtocolCodec.LifecycleFenceTracker();

        tracker.begin(firstA);
        tracker.begin(b);
        tracker.begin(secondA);

        var stale = assertThrows(ClientProtocolCodec.ProtocolException.class,
            () -> tracker.accept(firstA));
        assertEquals(ClientProtocolCodec.ErrorCode.STALE_OPERATION, stale.code());
        assertDoesNotThrow(() -> tracker.accept(secondA));
    }
}
