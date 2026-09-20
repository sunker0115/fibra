package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HostCapabilitySnapshotTest {
    @Test
    void keyPresenceDefinesAvailabilityWhileValuesRemainDescriptors() {
        var snapshot = HostCapabilitySnapshot.of(Map.of(
            "enabled-flag", false,
            "codec", Map.of("version", 2)));

        assertEquals(Set.of("enabled-flag", "codec"),
            snapshot.availableNames());
    }
}
