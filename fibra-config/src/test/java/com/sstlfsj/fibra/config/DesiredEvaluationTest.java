package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesiredEvaluationTest {
    @Test
    void localContextMakesTheSameReferenceResolveDifferentlyAndInjectsEntryMetadata() {
        var expression = LiteralValue.of(Map.of("$ref", "/value"));
        var first = DesiredInputEntry.builder("first", ref("sample"))
            .context(Map.of("value", LiteralValue.of("one")))
            .config(LiteralValue.of(Map.of("value", expression,
                "id", Map.of("$ref", "/entry/id"),
                "parent", Map.of("$ref", "/entry/parentId")))).build();
        var second = DesiredInputEntry.builder("second", ref("sample"))
            .context(Map.of("value", LiteralValue.of("two")))
            .config(expression).build();
        var graph = new DesiredInputGraph(List.of(
            DesiredInputGroup.builder("group").children(List.of(first, second)).build()));

        var evaluation = DesiredEvaluation.evaluate(graph, ConfigContextSnapshot.empty());

        assertEquals(LiteralValue.of(Map.of("value", "one", "id", "first", "parent", "group")),
            evaluation.require("first").resolvedConfig().orElseThrow());
        assertEquals(LiteralValue.of("two"),
            evaluation.require("second").resolvedConfig().orElseThrow());
        assertEquals(LiteralValue.of(true), graph.require("first").when());
    }

    @Test
    void groupConditionConjoinsWithChildrenAndCanBeReevaluatedWithoutChangingRawGraph() {
        var child = DesiredInputEntry.builder("worker", ref("sample"))
            .when(LiteralValue.of(Map.of("$ref", "/child")))
            .config(LiteralValue.of(Map.of("$ref", "/missing"))).build();
        var group = DesiredInputGroup.builder("group")
            .when(LiteralValue.of(Map.of("$ref", "/group")))
            .children(List.of(child)).build();
        var graph = new DesiredInputGraph(List.of(group));

        var stopped = DesiredEvaluation.evaluate(graph,
            snapshot(Map.of("group", false)));

        assertFalse(stopped.require("group").effective().enabled());
        assertFalse(stopped.require("worker").effective().enabled());
        assertTrue(stopped.require("worker").resolvedConfig().isEmpty());
        assertEquals(group, graph.require("group"));

        var failure = assertThrows(ConfigException.class, () -> DesiredEvaluation.evaluate(graph,
            snapshot(Map.of("group", true, "child", true))));
        assertEquals("CONTEXT_REFERENCE_MISSING", failure.diagnostic().code());
        assertEquals("worker", failure.diagnostic().entryId());

        var recovered = DesiredEvaluation.evaluate(graph,
            snapshot(Map.of("group", true, "child", true, "missing", "ready")));
        assertTrue(recovered.require("worker").effective().enabled());
        assertEquals(LiteralValue.of("ready"),
            recovered.require("worker").resolvedConfig().orElseThrow());
    }

    @Test
    void disabledAncestorSkipsInvalidDescendantConditionAndConfig() {
        var child = DesiredInputEntry.builder("worker", ref("sample"))
            .when(LiteralValue.of(Map.of("$ref", "/missing-condition")))
            .config(LiteralValue.of(Map.of("$ref", "/missing-config"))).build();
        var graph = new DesiredInputGraph(List.of(
            DesiredInputGroup.builder("group").enabled(false).children(List.of(child)).build()));

        var evaluation = DesiredEvaluation.evaluate(graph, ConfigContextSnapshot.empty());

        assertFalse(evaluation.require("worker").effective().enabled());
        assertTrue(evaluation.require("worker").resolvedConfig().isEmpty());
    }

    @Test
    void treeUpdatesAndBuildersPreserveRawConditionAndContext() {
        var when = LiteralValue.of(Map.of("$ref", "/enabled"));
        var context = Map.of("enabled", LiteralValue.of(true));
        var group = DesiredInputGroup.builder("group").when(when).context(context)
            .children(List.of(DesiredInputEntry.builder("worker", ref("sample")).build())).build();
        var include = DesiredInputInclude.builder("include").enabled(false).when(when).context(context)
            .content(new DesiredIncludeContent.Collected(List.of())).build();
        var graph = new DesiredInputGraph(List.of(group, include));

        var changed = graph.withEnabled("group", false).withEnabled("include", true);

        assertEquals(when, changed.require("group").when());
        assertEquals(context, changed.require("group").context());
        assertEquals(when, changed.require("include").when());
        assertEquals(context, changed.require("include").context());
        assertThrows(IllegalArgumentException.class, () -> DesiredInputEntry.builder("bad", ref("sample"))
            .context(Map.of("entry", LiteralValue.of("forged"))).build());
    }

    @Test
    void programmaticNodesRejectMalformedExpressionsBeforeEvaluation() {
        assertEquals("CONDITION_NOT_BOOLEAN", assertThrows(ConfigException.class, () ->
            DesiredInputGroup.builder("group").when(LiteralValue.of("yes")).build())
            .diagnostic().code());
        assertEquals("EXPRESSION_SHAPE_INVALID", assertThrows(ConfigException.class, () ->
            DesiredInputInclude.builder("include")
                .when(LiteralValue.of(Map.of("$eq", List.of(1)))).build())
            .diagnostic().code());
        assertEquals("EXPRESSION_SHAPE_INVALID", assertThrows(ConfigException.class, () ->
            DesiredInputEntry.builder("entry", ref("sample"))
                .config(LiteralValue.of(Map.of("$if", List.of(true, "one")))).build())
            .diagnostic().code());
    }

    private static ConfigContextSnapshot snapshot(Map<String, ?> values) {
        return ConfigContextSnapshot.of((LiteralValue.ObjectValue) LiteralValue.of(values));
    }

    private static PluginDefinitionRef ref(String definitionId) {
        return new PluginDefinitionRef("sample-plugin", "main", definitionId);
    }
}
