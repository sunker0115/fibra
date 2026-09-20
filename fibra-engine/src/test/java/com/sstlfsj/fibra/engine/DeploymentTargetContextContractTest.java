package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DeploymentTargetContextContractTest {
    @Test
    void configContextParticipatesInTargetIdentity() {
        var graph = new DesiredInputGraph(List.of());
        var first = DeploymentTarget.of(1, List.of(), graph,
            context(Map.of("region", "a")));
        var changed = DeploymentTarget.of(2, List.of(), graph,
            context(Map.of("region", "b")));

        assertNotEquals(first.targetDigest(), changed.targetDigest());
        assertFalse(first.hasSameContent(List.of(), graph,
            context(Map.of("region", "b"))));
    }

    @Test
    void canonicalContextOrderDoesNotChangeTargetIdentity() {
        var graph = new DesiredInputGraph(List.of());
        var left = new LinkedHashMap<String, Object>();
        left.put("a", 1);
        left.put("b", "two");
        var right = new LinkedHashMap<String, Object>();
        right.put("b", "two");
        right.put("a", 1);

        var first = DeploymentTarget.of(1, List.of(), graph,
            context(left));
        var second = DeploymentTarget.of(2, List.of(), graph,
            context(right));

        assertEquals(first.targetDigest(), second.targetDigest());
        assertEquals(first.configContext(), second.configContext());
    }

    private static ConfigContextSnapshot context(Map<String, ?> values) {
        return ConfigContextSnapshot.of(
            (LiteralValue.ObjectValue) LiteralValue.of(values));
    }
}
