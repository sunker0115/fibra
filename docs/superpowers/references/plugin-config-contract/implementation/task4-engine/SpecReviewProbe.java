package com.sstlfsj.fibra.engine;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.engine.deployment.DeploymentTarget;
import com.sstlfsj.fibra.engine.observation.TargetConvergence;
import static com.sstlfsj.fibra.engine.EngineConfigurationContractTest.*;
import static com.sstlfsj.fibra.engine.deployment.ConfigContractException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent review probe: no repository edits; uses frozen Engine test fixture. */
public final class SpecReviewProbe {
    public static void main(String[] args) throws Exception {
        var test = new EngineConfigurationContractTest();
        test.root = Files.createTempDirectory("fibra-spec-review-");
        try (var f = test.new Fixture()) {
            var r = f.publish("sample", Map.of("main", Map.of("run", schema("integer"))));
            f.store.delegate.save(0, DeploymentTarget.of(1, List.of(select(r, true)),
                graph(entry("one", REF, "invalid")), ConfigContextSnapshot.empty()));
            f.start();
            assertEquals(TargetConvergence.BLOCKED, f.engine.snapshot().targetConvergence());
            var before = f.engine.published().current();
            assertEquals(source("integer"), f.engine.describeConfiguration(REF, 1).block().source());
            assertEquals(before, f.engine.published().current());
            assertEquals(0, f.provider.creates);
        }
        for (boolean absent : List.of(false, true)) {
            var f = test.new Fixture();
            try {
                var r = f.publishText("sample", absent ? null : "{malformed");
                f.start(); f.apply(List.of(select(r, false)), graph(), ConfigContextSnapshot.empty());
                f.closeFailure = new HostileError();
                var error = assertCode(CLEANUP_FAILED, f.engine.describeConfiguration(REF, 1));
                assertEquals("configuration contract failed: CLEANUP_FAILED; prior=" +
                    (absent ? "MISSING_CONTRACT" : "INVALID_CONTRACT"), error.getMessage());
                assertSame(error, assertCode(CLEANUP_FAILED, f.engine.closeAsync()));
                assertEquals(0, f.provider.driverCloses);
                assertEquals(0, f.store.closes);
            } finally { f.packages.close(); }
        }
        System.out.println("PASS: BLOCKED query preserves operation; INVALID and MISSING double failures retain fixed secondary code and shutdown barrier");
    }
}
