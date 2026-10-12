package com.sstlfsj.fibra.engine.deployment;

import com.sstlfsj.fibra.artifact.*;
import com.sstlfsj.fibra.config.*;
import com.sstlfsj.fibra.value.LiteralValue;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuiltInPluginPackageTest {
    @Test
    void finalIdentityMatchesFrozenCanonicalContract() throws Exception {
        var value = metadata(List.of(facet("main", "run", source("profile", Map.of("type", "integer")))));
        var canonical = "{\"facets\":[{\"capabilities\":[],\"configSchemas\":{\"run\":{\"profile\":\"profile\",\"schema\":{\"type\":\"integer\"}}},\"definitionIds\":[\"run\"],\"dependencies\":[],\"facetId\":\"main\",\"runtime\":\"java\",\"target\":\"host\"}],\"implementationDigest\":\""
            + "a".repeat(64) + "\",\"pluginId\":\"core\",\"version\":\"1\"}";
        var digest = MessageDigest.getInstance("SHA-256").digest(
            ("fibra-built-in-package-v2\0" + canonical).getBytes(StandardCharsets.UTF_8));
        assertEquals(HexFormat.of().formatHex(digest), value.packageDigest());
        assertEquals(value.packageDigest(), value.selection(true).packageRevision());
        assertEquals("built-in:core:" + value.packageDigest() + ":main", value.artifactId(new FacetId("main")).value());
    }

    @Test
    void schemaProfileAndFacetContentAssociationArePartOfIdentity() {
        var integer = source("profile", Map.of("type", "integer"));
        var text = source("profile", Map.of("type", "string"));
        var base = metadata(List.of(facet("main", "run", integer)));
        assertNotEquals(base.packageDigest(), metadata(List.of(facet("main", "run", text))).packageDigest());
        assertNotEquals(base.packageDigest(), metadata(List.of(facet("main", "run",
            source("other", Map.of("type", "integer"))))).packageDigest());
        assertNotEquals(metadata(List.of(facet("one", "run", integer), facet("two", "run", text))).packageDigest(),
            metadata(List.of(facet("two", "run", integer), facet("one", "run", text))).packageDigest());
        assertNotEquals(base.packageDigest(), metadata(List.of(facet("main", "other", integer))).packageDigest());
        assertNotEquals(base.packageDigest(), BuiltInPluginPackage.builder().pluginId(new PluginId("core"))
            .version("2").implementationDigest("a".repeat(64)).facets(base.facets()).build().packageDigest());
        assertNotEquals(base.packageDigest(), BuiltInPluginPackage.builder().pluginId(new PluginId("other"))
            .version("1").implementationDigest("a".repeat(64)).facets(base.facets()).build().packageDigest());
        assertNotEquals(base.packageDigest(), BuiltInPluginPackage.builder().pluginId(new PluginId("core"))
            .version("1").implementationDigest("b".repeat(64)).facets(base.facets()).build().packageDigest());
    }

    @Test
    void collectionOrderAndEquivalentLiteralNumbersDoNotChangeIdentity() {
        var one = new FacetDependency(new PluginId("a"), new FacetId("z"));
        var two = new FacetDependency(new PluginId("a"), new FacetId("a"));
        var three = new FacetDependency(new PluginId("b"), new FacetId("a"));
        var schema = source("profile", Map.of("minimum", 1, "type", "integer"));
        var reversedSchema = new LinkedHashMap<String, Object>();
        reversedSchema.put("type", "integer");
        reversedSchema.put("minimum", new BigDecimal("1.00"));
        var contracts = new LinkedHashMap<String, ConfigSchemaSource>();
        contracts.put("z", schema); contracts.put("a", schema);
        var first = BuiltInFacet.builder(new FacetId("main"), new RuntimeId("java"), new ExecutionTarget("host"))
            .definitionIds(new LinkedHashSet<>(List.of("z", "a"))).requiredCapabilities(new LinkedHashSet<>(List.of("z", "a")))
            .dependencies(List.of(one, three, two)).configSchemas(contracts).build();
        var second = BuiltInFacet.builder(new FacetId("main"), new RuntimeId("java"), new ExecutionTarget("host"))
            .definitionIds(new LinkedHashSet<>(List.of("a", "z"))).requiredCapabilities(new LinkedHashSet<>(List.of("a", "z")))
            .dependencies(List.of(two, one, three)).configSchemas(Map.of("a", source("profile", reversedSchema),
                "z", source("profile", reversedSchema))).build();
        var extra = facet("extra", "run", schema);
        assertEquals(metadata(List.of(first, extra)).packageDigest(), metadata(List.of(extra, second)).packageDigest());
        var changed = BuiltInFacet.builder(first.facetId(), first.runtimeId(), new ExecutionTarget("client"))
            .definitionIds(first.definitionIds()).requiredCapabilities(first.requiredCapabilities())
            .dependencies(first.dependencies()).configSchemas(first.configSchemas()).build();
        assertNotEquals(metadata(List.of(first)).packageDigest(), metadata(List.of(changed)).packageDigest());
    }

    @Test
    void metadataAndNestedSourcesAreImmutableAndOrphanKeysAreRejected() {
        var nested = new LinkedHashMap<String, Object>(); nested.put("type", "integer");
        var raw = new LinkedHashMap<String, Object>(); raw.put("properties", Map.of("value", nested));
        var source = source("profile", raw);
        var sources = new LinkedHashMap<String, ConfigSchemaSource>(); sources.put("run", source);
        var definitions = new HashSet<>(Set.of("run"));
        var facet = BuiltInFacet.builder(new FacetId("main"), new RuntimeId("java"), new ExecutionTarget("host"))
            .definitionIds(definitions).configSchemas(sources).build();
        var facets = new ArrayList<>(List.of(facet));
        var value = metadata(facets);
        var digest = value.packageDigest();
        nested.put("type", "string"); raw.clear(); sources.clear(); definitions.clear(); facets.clear();
        assertEquals(digest, metadata(value.facets()).packageDigest());
        assertEquals("{\"properties\":{\"value\":{\"type\":\"integer\"}}}", facet.configSchemas().get("run").schema().canonicalJson());
        assertThrows(UnsupportedOperationException.class, () -> facet.configSchemas().clear());
        assertThrows(UnsupportedOperationException.class, () -> source.schema().values().clear());
        assertThrows(IllegalArgumentException.class, () -> BuiltInFacet.builder(new FacetId("main"),
            new RuntimeId("java"), new ExecutionTarget("host")).definitionIds(Set.of("run"))
            .configSchemas(Map.of("orphan", source)).build());
    }

    @Test
    void missingAndSemanticallyInvalidContractsRemainConstructible() {
        var missing = BuiltInFacet.builder(new FacetId("main"), new RuntimeId("java"), new ExecutionTarget("host"))
            .definitionIds(Set.of("run")).build();
        assertEquals(Map.of(), missing.configSchemas());
        assertDoesNotThrow(() -> metadata(List.of(missing)));
        assertDoesNotThrow(() -> metadata(List.of(facet("main", "run", source("future-profile", Map.of("type", 12))))));
        assertDoesNotThrow(() -> metadata(List.of(facet("main", "run", source("fibra-config-1", Map.of("type", 12))))));
    }

    @Test
    void originalMetadataShapeConstraintsStillApply() {
        var facet = facet("main", "run", source("profile", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> metadata(List.of()));
        assertThrows(IllegalArgumentException.class, () -> metadata(List.of(facet, facet)));
        for (var digest : List.of("", "a", "A".repeat(64), "g".repeat(64))) {
            assertThrows(IllegalArgumentException.class, () -> BuiltInPluginPackage.builder().pluginId(new PluginId("core"))
                .version("1").implementationDigest(digest).facets(List.of(facet)).build());
        }
        assertThrows(IllegalArgumentException.class, () -> BuiltInPluginPackage.builder().pluginId(new PluginId("core"))
            .version(" ").implementationDigest("a".repeat(64)).facets(List.of(facet)).build());
        var dependency = new FacetDependency(new PluginId("core"), new FacetId("api"));
        assertThrows(IllegalArgumentException.class, () -> BuiltInFacet.builder(new FacetId("main"), new RuntimeId("java"),
            new ExecutionTarget("host")).dependencies(List.of(dependency, dependency)).build());
        assertThrows(IllegalArgumentException.class, () -> BuiltInFacet.builder(new FacetId("main"), new RuntimeId("java"),
            new ExecutionTarget("host")).definitionIds(Set.of(" ")).build());
        assertThrows(IllegalArgumentException.class, () -> BuiltInFacet.builder(new FacetId("main"), new RuntimeId("java"),
            new ExecutionTarget("host")).requiredCapabilities(Set.of(" ")).build());
    }

    private static ConfigSchemaSource source(String profile, Map<String, ?> schema) {
        return new ConfigSchemaSource(profile, (LiteralValue.ObjectValue) LiteralValue.of(schema));
    }

    private static BuiltInFacet facet(String id, String definition, ConfigSchemaSource schema) {
        return BuiltInFacet.builder(new FacetId(id), new RuntimeId("java"), new ExecutionTarget("host"))
            .definitionIds(Set.of(definition)).configSchemas(Map.of(definition, schema)).build();
    }

    @Test
    void facetRenameChangesContentIdentityWithoutImplementationChange() {
        var first = metadata(List.of(BuiltInFacet.builder(new FacetId("first"), new RuntimeId("java"),
            new ExecutionTarget("host")).build()));
        var second = metadata(List.of(BuiltInFacet.builder(new FacetId("second"), new RuntimeId("java"),
            new ExecutionTarget("host")).build()));
        assertNotEquals(first.packageDigest(), second.packageDigest());
        assertNotEquals("a".repeat(64), first.packageDigest());
    }

    @Test
    void multipleFacetsKeepIdentityCapabilitiesAndDependencyOrder() {
        var api = BuiltInFacet.builder(new FacetId("api"), new RuntimeId("java"), new ExecutionTarget("host"))
            .definitionIds(Set.of("service")).requiredCapabilities(Set.of("storage")).build();
        var impl = BuiltInFacet.builder(new FacetId("impl"), new RuntimeId("java"), new ExecutionTarget("host"))
            .definitionIds(Set.of("service")).dependencies(List.of(new FacetDependency(
                new PluginId("core"), new FacetId("api")))).build();
        var builtIn = metadata(List.of(impl, api));
        var target = DeploymentTarget.of(1, List.of(builtIn.selection(true)), new DesiredInputGraph(List.of()),
            ConfigContextSnapshot.empty());
        var compiled = new DeploymentTargetCompiler().compile(target, List.of(), List.of(builtIn));
        assertEquals(List.of(builtIn.artifactId(api.facetId()), builtIn.artifactId(impl.facetId())),
            compiled.dependencyFirst());
        assertEquals(Set.of("storage"), compiled.builtInFacets().get(builtIn.artifactId(api.facetId()))
            .facet().requiredCapabilities());
        assertEquals(builtIn.artifactId(api.facetId()), compiled.builtInFacets()
            .get(builtIn.artifactId(impl.facetId())).dependencies().getFirst().artifactId());
        assertNotEquals(builtIn.artifactId(api.facetId()), builtIn.artifactId(impl.facetId()));
        assertThrows(UnsupportedOperationException.class, () -> builtIn.facets().clear());
    }

    @Test
    void builtInFacetCyclesAndCrossProviderFragmentsAreRejected() {
        var one = BuiltInFacet.builder(new FacetId("one"), new RuntimeId("java"), new ExecutionTarget("host"))
            .dependencies(List.of(new FacetDependency(new PluginId("core"), new FacetId("two")))).build();
        var two = BuiltInFacet.builder(new FacetId("two"), new RuntimeId("java"), new ExecutionTarget("host"))
            .dependencies(List.of(new FacetDependency(new PluginId("core"), new FacetId("one")))).build();
        var value = metadata(List.of(one, two));
        var target = DeploymentTarget.of(1, List.of(value.selection(true)), new DesiredInputGraph(List.of()),
            ConfigContextSnapshot.empty());
        assertThrows(IllegalArgumentException.class,
            () -> new DeploymentTargetCompiler().compile(target, List.of(), List.of(value)));
        var foreign = BuiltInFacet.builder(new FacetId("foreign"), new RuntimeId("node"), new ExecutionTarget("host")).build();
        assertThrows(IllegalArgumentException.class, () -> metadata(List.of(one, foreign)));
    }

    @Test
    void providerRevisionMustMatchEvenWhenItsPackageIsDisabled() {
        var value = metadata(List.of(BuiltInFacet.builder(new FacetId("host"), new RuntimeId("java"),
            new ExecutionTarget("host")).definitionIds(Set.of("sample")).build()));
        for (var enabled : List.of(true, false)) {
            var target = DeploymentTarget.of(1, List.of(new PluginSelection(value.pluginId(), "a".repeat(64), enabled)),
                new DesiredInputGraph(List.of()), ConfigContextSnapshot.empty());
            var error = assertThrows(IllegalArgumentException.class,
                () -> new DeploymentTargetCompiler().compile(target, List.of(), List.of(value)));
            assertTrue(error.getMessage().contains("selected built-in package revision is missing"));
        }
    }

    private static BuiltInPluginPackage metadata(List<BuiltInFacet> facets) {
        return BuiltInPluginPackage.builder().pluginId(new PluginId("core")).version("1")
            .implementationDigest("a".repeat(64)).facets(facets).build();
    }
}
