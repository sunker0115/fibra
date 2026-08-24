package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FibraEngineStatusTest {
    @Test
    void statusAndResultsDefensivelyCopyCollections() {
        var failures = new ArrayList<FibraEngineFailure>();
        failures.add(new FibraEngineFailure(FibraEngineFailureStage.STARTUP,
            Optional.empty(), "failed", Instant.EPOCH));
        var status = new FibraEngineStatus(FibraEngineState.DEGRADED, Optional.empty(),
            Optional.empty(), failures);
        failures.clear();
        assertEquals(1, status.failures().size());
        assertThrows(UnsupportedOperationException.class,
            () -> status.failures().add(status.failures().getFirst()));

        var changed = new ArrayList<>(List.of("plugin"));
        var result = new FibraDeploymentResult("deployment", "1.0.0", "revision", changed);
        changed.clear();
        assertEquals(List.of("plugin"), result.changedArtifactIds());
    }
}
