package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredEvaluation;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeTargetSliceTest {
    @Test
    void rejectsEvaluationFromAnotherConfigContext() {
        var target = target();
        assertThrows(IllegalArgumentException.class, () -> slice(target,
            ConfigContextSnapshot.empty()));
    }

    @Test
    void acceptsEquivalentConfigContext() {
        var target = target();
        var equivalent = context();
        assertEquals(target.configContext(), slice(target, equivalent).desired().context());
    }

    private static DeploymentTarget target() {
        return DeploymentTarget.of(1, List.of(), new DesiredInputGraph(List.of()), context());
    }

    private static ConfigContextSnapshot context() {
        return ConfigContextSnapshot.of((LiteralValue.ObjectValue)
            LiteralValue.of(Map.of("region", "a")));
    }

    private static RuntimeTargetSlice slice(DeploymentTarget target, ConfigContextSnapshot context) {
        return RuntimeTargetSlice.builder(new RuntimeId("test"), target)
            .desired(DesiredEvaluation.evaluate(target.desiredGraph(), context))
            .capabilities(HostCapabilitySnapshot.empty()).affectedEntryIds(Set.of())
            .unitDependencies(Map.of()).build();
    }
}
