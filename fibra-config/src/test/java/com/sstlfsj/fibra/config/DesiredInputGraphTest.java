package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesiredInputGraphTest {
    @Test
    void preservesTreeAndCalculatesInheritedPolicyWithoutChangingLocalEntry() {
        var entry = DesiredInputEntry.builder("worker", ref("sample")).enabled(true)
            .realms(Map.of("shared", LiteralValue.of("entry"))).build();
        var group = DesiredInputGroup.builder("agents").enabled(false)
            .realms(Map.of("shared", LiteralValue.of("group"), "region", LiteralValue.of("cn")))
            .children(List.of(entry)).build();
        var graph = new DesiredInputGraph(List.of(group));

        assertEquals(List.of(group), graph.roots());
        org.junit.jupiter.api.Assertions.assertTrue(entry.enabled());
        assertFalse(graph.effective("agents").enabled());
        assertEquals("agents", graph.effective("agents").entryId());
        var effective = graph.effective("worker");
        assertFalse(effective.enabled());
        assertEquals("worker", effective.entryId());
        assertEquals("agents", effective.realms().get("region").ownerEntryId());
        assertEquals("entry", effective.realms().get("shared").value().toJava());
        assertEquals("worker", effective.realms().get("shared").ownerEntryId());
    }

    @Test
    void movesAcrossIncludeNamespaceAndRejectsAnEnabledUncollectedInclude() {
        var source = DesiredInputInclude.builder("source").enabled(false)
            .content(new DesiredIncludeContent.Collected(List.of(
                DesiredInputEntry.builder("worker", ref("sample")).build()))).build();
        var target = DesiredInputInclude.builder("target").enabled(false)
            .content(new DesiredIncludeContent.Collected(List.of())).build();
        var uncollected = DesiredInputInclude.builder("uncollected").enabled(false)
            .content(DesiredIncludeContent.Uncollected.INSTANCE).build();
        var graph = new DesiredInputGraph(List.of(source, target, uncollected));

        assertThrows(ConfigException.class, () -> graph.withEnabled("uncollected", true));
        assertThrows(ConfigException.class, () -> graph.upsert("uncollected",
            DesiredInputEntry.builder("new", ref("sample")).build()));
        assertThrows(ConfigException.class, () -> graph.move("source:worker", "uncollected", 0));
        var moved = graph.move("source:worker", "target", 0);

        assertThrows(IllegalArgumentException.class, () -> moved.require("source:worker"));
        assertEquals("worker", moved.require("target:worker").id());
    }

    @Test
    void upsertAndRemoveKeepTheCompleteTree() {
        var group = DesiredInputGroup.builder("group").children(List.of(
            DesiredInputEntry.builder("first", ref("sample")).build())).build();
        var graph = new DesiredInputGraph(List.of(group));

        var updated = graph.upsert("group", DesiredInputEntry.builder("second", ref("sample")).build())
            .upsert("group", DesiredInputEntry.builder("first", ref("changed")).build());

        assertEquals(List.of("first", "second"), updated.plugins().keySet().stream().toList());
        assertEquals(ref("changed"), updated.plugins().get("first").definitionRef());
        assertEquals(List.of("second"), updated.remove("first").plugins().keySet().stream().toList());
        assertThrows(IllegalArgumentException.class,
            () -> updated.upsert(null, DesiredInputEntry.builder("second", ref("sample")).build()));
    }

    @Test
    void rejectsBlankInterceptKeysForProgrammaticNodes() {
        assertThrows(IllegalArgumentException.class, () -> DesiredInputEntry.builder("worker", ref("sample"))
            .intercepts(Map.of(" ", LiteralValue.of(true))).build());
    }

    @Test
    void movesWithPostRemovalPositionsAndRejectsInvalidDestinations() {
        var outer = DesiredInputGroup.builder("outer").children(List.of(
            DesiredInputGroup.builder("inner").children(List.of(
                DesiredInputEntry.builder("nested", ref("sample")).build())).build())).build();
        var graph = new DesiredInputGraph(List.of(outer,
            DesiredInputEntry.builder("a", ref("sample")).build(),
            DesiredInputEntry.builder("b", ref("sample")).build()));

        assertEquals(List.of("outer", "b", "a"), graph.move("a", null, 2).roots().stream()
            .map(DesiredInputNode::id).toList());
        assertThrows(IllegalArgumentException.class, () -> graph.move("outer", "inner", 0));
        assertThrows(IllegalArgumentException.class, () -> graph.move("b", "a", 0));
        assertThrows(IllegalArgumentException.class, () -> graph.move("a", null, 3));
    }

    @Test
    void rejectsNamespaceConflictsAndRetainsCollectedChildrenWhenAncestorStops() {
        var source = DesiredInputInclude.builder("source").enabled(false)
            .content(new DesiredIncludeContent.Collected(List.of(
                DesiredInputEntry.builder("worker", ref("sample")).build()))).build();
        var target = DesiredInputInclude.builder("target").enabled(false)
            .content(new DesiredIncludeContent.Collected(List.of(
                DesiredInputEntry.builder("worker", ref("sample")).build()))).build();
        var group = DesiredInputGroup.builder("group").children(List.of(source)).build();
        var graph = new DesiredInputGraph(List.of(group, target));

        assertThrows(IllegalArgumentException.class, () -> graph.move("source:worker", "target", 1));
        var stopped = graph.withEnabled("group", false);
        assertEquals("worker", stopped.require("source:worker").id());
        org.junit.jupiter.api.Assertions.assertTrue(stopped.require("source:worker").enabled());
        assertFalse(stopped.effective("source:worker").enabled());
        assertThrows(IllegalArgumentException.class, () -> stopped.remove("group").require("source"));
    }

    private static PluginDefinitionRef ref(String definitionId) {
        return new PluginDefinitionRef("sample-plugin", "main", definitionId);
    }
}
