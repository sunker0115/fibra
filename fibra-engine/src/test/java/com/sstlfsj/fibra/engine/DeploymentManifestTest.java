package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.DesiredInputGroup;
import com.sstlfsj.fibra.config.DesiredInputInclude;
import com.sstlfsj.fibra.config.DesiredIncludeContent;
import com.sstlfsj.fibra.config.PublicationRequirement;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentManifestTest {
    private static final String FIRST = "a".repeat(64);
    private static final String SECOND = "b".repeat(64);

    @Test
    void roundTripsEveryDesiredFieldIncludingDisabledUnknownDefinitions() {
        var entry = DesiredInputEntry.builder("instance", "not-installed")
            .enabled(false).publicationRequirement(PublicationRequirement.PENDING_ALLOWED)
            .when(LiteralValue.of(Map.of("$ref", "/enabled")))
            .context(Map.of("enabled", LiteralValue.of(true)))
            .config(LiteralValue.of(Map.of("precise", new BigDecimal("12345678901234567890.123456789"),
                "values", List.of(true, "text\u0000\ud800", LiteralValue.NullValue.INSTANCE))))
            .realms(Map.of("tenant", LiteralValue.of("one")))
            .intercepts(Map.of("timeout", LiteralValue.of(30)))
            .build();
        var manifest = new DeploymentManifest(Map.of(new ArtifactId("plugin"), FIRST),
            new DesiredInputGraph(List.of(entry)));

        var encoded = DeploymentManifestCodec.encode(manifest);
        var restored = DeploymentManifestCodec.decode(encoded);

        assertEquals(manifest, restored);
        assertEquals(manifest.revision(), restored.revision());
        assertArrayEquals(encoded, DeploymentManifestCodec.encode(restored));
    }

    @Test
    void canonicalIdentityIgnoresObjectInsertionOrderAndNumericScale() {
        var forward = new LinkedHashMap<ArtifactId, String>();
        forward.put(new ArtifactId("a"), FIRST);
        forward.put(new ArtifactId("b"), SECOND);
        var reverse = new LinkedHashMap<ArtifactId, String>();
        reverse.put(new ArtifactId("b"), SECOND);
        reverse.put(new ArtifactId("a"), FIRST);
        var first = new DeploymentManifest(forward, graph(entry(LiteralValue.of(new BigDecimal("1.00")))));
        var second = new DeploymentManifest(reverse, graph(entry(LiteralValue.of(1))));

        assertEquals(first.revision(), second.revision());
        assertArrayEquals(DeploymentManifestCodec.encode(first), DeploymentManifestCodec.encode(second));
        forward.clear();
        assertEquals(2, first.artifacts().size());
        assertThrows(UnsupportedOperationException.class, () -> first.artifacts().clear());
    }

    @Test
    void everyEffectiveInputChangesTheDeploymentIdentity() {
        var original = entry(LiteralValue.of("old"));
        var manifest = new DeploymentManifest(Map.of(new ArtifactId("a"), FIRST), graph(original));
        var variants = List.of(
            original.toBuilder().enabled(false).build(),
            original.toBuilder().when(LiteralValue.of(false)).build(),
            original.toBuilder().context(Map.of("tenant", LiteralValue.of("x"))).build(),
            original.toBuilder().publicationRequirement(PublicationRequirement.PENDING_ALLOWED).build(),
            original.toBuilder().config(LiteralValue.of("new")).build(),
            original.toBuilder().realms(Map.of("tenant", LiteralValue.of("x"))).build(),
            original.toBuilder().intercepts(Map.of("timeout", LiteralValue.of(10))).build(),
            DesiredInputEntry.builder("renamed", original.definitionName()).config(original.config()).build(),
            DesiredInputEntry.builder(original.id(), "other-definition").config(original.config()).build());
        for (var variant : variants) {
            assertNotEquals(manifest.revision(),
                new DeploymentManifest(manifest.artifacts(), graph(variant)).revision());
        }
        assertNotEquals(manifest.revision(),
            new DeploymentManifest(Map.of(new ArtifactId("a"), SECOND), graph(original)).revision());
        assertNotEquals(manifest.revision(), new DeploymentManifest(Map.of(), graph(original)).revision());
    }

    @Test
    void preservesInstanceOrderAndAnEmptyTarget() {
        var first = entry(LiteralValue.NullValue.INSTANCE);
        var second = DesiredInputEntry.builder("second", "definition").build();
        var forward = new DeploymentManifest(Map.of(), new DesiredInputGraph(List.of(first, second)));
        var reverse = new DeploymentManifest(Map.of(), new DesiredInputGraph(List.of(second, first)));
        assertNotEquals(forward.revision(), reverse.revision());
        assertEquals(forward, DeploymentManifestCodec.decode(DeploymentManifestCodec.encode(forward)));
        var empty = new DeploymentManifest(Map.of(), new DesiredInputGraph(List.of()));
        assertEquals(empty, DeploymentManifestCodec.decode(DeploymentManifestCodec.encode(empty)));
    }

    @Test
    void roundTripsTreeOwnershipAndCollectedAndUncollectedIncludes() {
        var plugin = entry(LiteralValue.of("input"));
        var group = DesiredInputGroup.builder("group")
            .when(LiteralValue.of(Map.of("$defined", "/tenant")))
            .context(Map.of("tenant", LiteralValue.of("group")))
            .realms(Map.of("message", LiteralValue.of(true)))
            .intercepts(Map.of("message", LiteralValue.of("group-policy")))
            .children(List.of(plugin)).build();
        var collected = DesiredInputInclude.builder("bundle")
            .when(LiteralValue.of(false))
            .context(Map.of("source", LiteralValue.of("bundle")))
            .content(new DesiredIncludeContent.Collected(List.of(group))).build();
        var uncollected = DesiredInputInclude.builder("missing")
            .enabled(false).content(new DesiredIncludeContent.Uncollected()).build();
        var graph = new DesiredInputGraph(List.of(collected, uncollected));
        var manifest = new DeploymentManifest(Map.of(), graph);

        var restored = DeploymentManifestCodec.decode(DeploymentManifestCodec.encode(manifest));

        assertEquals(manifest, restored);
        assertEquals(List.of("bundle:instance"), restored.desiredGraph().plugins().keySet().stream().toList());
        assertEquals("bundle:group", restored.desiredGraph().effective("bundle:instance").parentId());
        assertEquals("bundle:group", restored.desiredGraph().effective("bundle:instance")
            .realms().get("message").ownerEntryId());
        assertArrayEquals(DeploymentManifestCodec.encode(manifest), DeploymentManifestCodec.encode(restored));
    }

    @Test
    void deploymentIdentityIncludesParentOwnershipAndIncludeCollectionState() {
        var plugin = entry(LiteralValue.NullValue.INSTANCE);
        var group = DesiredInputGroup.builder("group").children(List.of(plugin)).build();
        var first = new DeploymentManifest(Map.of(), new DesiredInputGraph(List.of(group)));
        var moved = new DeploymentManifest(Map.of(), new DesiredInputGraph(List.of(
            DesiredInputGroup.builder("group").build(), plugin)));
        assertEquals(first.desiredGraph().plugins(), moved.desiredGraph().plugins());
        assertNotEquals(first.revision(), moved.revision());

        var missing = DesiredInputInclude.builder("bundle").enabled(false)
            .content(new DesiredIncludeContent.Uncollected()).build();
        var empty = DesiredInputInclude.builder("bundle").enabled(false)
            .content(new DesiredIncludeContent.Collected(List.of())).build();
        assertNotEquals(new DeploymentManifest(Map.of(), new DesiredInputGraph(List.of(missing))).revision(),
            new DeploymentManifest(Map.of(), new DesiredInputGraph(List.of(empty))).revision());
    }

    @Test
    void rejectsMalformedNestedNodesAndIncludeContent() {
        for (var node : List.of(
            """
            {"kind":"other","id":"x","enabled":true,"when":true,"context":{},"realms":{},"intercepts":{}}
            """,
            """
            {"kind":"group","id":"x","enabled":true,"when":true,"context":{},"realms":{},"intercepts":{}}
            """,
            """
            {"kind":"include","id":"x","enabled":false,"when":true,"context":{},"realms":{},"intercepts":{},
             "content":{"state":"collected"}}
            """,
            """
            {"kind":"include","id":"x","enabled":false,"when":true,"context":{},"realms":{},"intercepts":{},
             "content":{"state":"uncollected","children":[]}}
            """,
            """
            {"kind":"include","id":"x","enabled":false,"when":true,"context":{},"realms":{},"intercepts":{},
             "content":{"state":"unknown"}}
            """,
            """
            {"kind":"include","id":"x","enabled":true,"when":true,"context":{},"realms":{},"intercepts":{},
             "content":{"state":"uncollected"}}
            """)) {
            var json = "{\"format\":3,\"artifacts\":{},\"desired\":[" + node + "]}";
            assertThrows(IllegalArgumentException.class, () -> DeploymentManifestCodec.decode(
                json.getBytes(StandardCharsets.UTF_8)), json);
        }
    }

    @Test
    void rejectsAmbiguousCorruptOrUnrecognizedPersistedInput() {
        for (var json : List.of(
            "{}", "null", "[]",
            "{\"format\":1,\"artifacts\":{},\"desired\":[]}",
            "{\"format\":2,\"artifacts\":{},\"desired\":[]}",
            "{\"format\":4,\"artifacts\":{},\"desired\":[]}",
            "{\"format\":3,\"format\":3,\"artifacts\":{},\"desired\":[]}",
            "{\"format\":3,\"artifacts\":{},\"desired\":[]} {}",
            "{\"format\":3,\"artifacts\":{},\"desired\":[],\"unknown\":true}")) {
            assertThrows(IllegalArgumentException.class, () -> DeploymentManifestCodec.decode(
                json.getBytes(StandardCharsets.UTF_8)), json);
        }
        assertThrows(IllegalArgumentException.class, () -> new DeploymentManifest(
            Map.of(new ArtifactId("a"), "../not-a-revision"), new DesiredInputGraph(List.of())));
    }

    @Test
    void rejectsPersistedMalformedConditionAndConfigExpressions() {
        for (var node : List.of(
            """
            {"kind":"group","id":"x","enabled":false,"when":"yes","context":{},"realms":{},
             "intercepts":{},"children":[]}
            """,
            """
            {"kind":"plugin","id":"x","enabled":false,"when":true,"context":{},"realms":{},
             "intercepts":{},"definitionName":"sample","config":{"$if":[true,"one"]},
             "publicationRequirement":"ACTIVE_REQUIRED"}
            """)) {
            var json = "{\"format\":3,\"artifacts\":{},\"desired\":[" + node + "]}";
            assertThrows(IllegalArgumentException.class, () -> DeploymentManifestCodec.decode(
                json.getBytes(StandardCharsets.UTF_8)), json);
        }
    }

    private static DesiredInputEntry entry(LiteralValue value) {
        return DesiredInputEntry.builder("instance", "definition").config(value).build();
    }

    private static DesiredInputGraph graph(DesiredInputEntry entry) {
        return new DesiredInputGraph(List.of(entry));
    }
}
