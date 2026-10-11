package com.sstlfsj.fibra.engine.deployment;

import com.sstlfsj.fibra.artifact.ExecutionTarget;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.engine.runtime.RuntimeDefinitionQuery;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageDefinitionCatalogTest {
    private static final String REVISION = "a".repeat(64);
    private static final PluginId PLUGIN = new PluginId("plugin");
    private static final FacetId FACET = new FacetId("main");
    private static final RuntimeId RUNTIME = new RuntimeId("java");
    private static final ExecutionTarget TARGET = new ExecutionTarget("host");

    @Test
    void sortsFacetsAndOriginalDefinitionNamesWithoutRenaming() {
        var catalog = catalog(List.of(
            facet("z", List.of("z", "name", " name", "Name")),
            facet("a", List.of("last", "first"))));

        assertEquals(List.of(new FacetId("a"), new FacetId("z")),
            catalog.facets().stream().map(FacetDefinitionNames::facetId).toList());
        assertEquals(List.of("first", "last"), catalog.facets().get(0).definitionIds());
        assertEquals(List.of(" name", "Name", "name", "z"),
            catalog.facets().get(1).definitionIds());
        assertEquals(7, catalog.targetRevision());
        assertEquals(PLUGIN, catalog.pluginId());
        assertEquals(REVISION, catalog.packageRevision());
        assertEquals(RUNTIME, catalog.facets().get(0).runtimeId());
        assertEquals(TARGET, catalog.facets().get(0).executionTarget());
    }

    @Test
    void copiesBothInputListsAndRejectsMutationAtEveryDepth() {
        var definitions = new ArrayList<>(List.of("b", "a"));
        var facet = facet("main", definitions);
        var facets = new ArrayList<>(List.of(facet));
        var catalog = catalog(facets);
        definitions.clear();
        facets.clear();

        assertEquals(List.of(facet), catalog.facets());
        assertEquals(List.of("a", "b"), catalog.facets().get(0).definitionIds());
        assertAll(
            () -> assertThrows(UnsupportedOperationException.class,
                () -> catalog.facets().clear()),
            () -> assertThrows(UnsupportedOperationException.class,
                () -> catalog.facets().add(facet)),
            () -> assertThrows(UnsupportedOperationException.class,
                () -> catalog.facets().set(0, facet)),
            () -> assertThrows(UnsupportedOperationException.class,
                () -> facet.definitionIds().clear()),
            () -> assertThrows(UnsupportedOperationException.class,
                () -> facet.definitionIds().add("c")),
            () -> assertThrows(UnsupportedOperationException.class,
                () -> facet.definitionIds().set(0, "c")));
    }

    @Test
    void preservesEmptyContractOnlyFacetsAndAllowsAnEmptyCatalog() {
        var empty = facet("contracts", List.of());
        assertEquals(List.of(empty), catalog(List.of(empty)).facets());
        assertEquals(List.of(), catalog(List.of()).facets());
    }

    @Test
    void rejectsDuplicateFacetIdsEvenWhenTheirRuntimeOrDefinitionsDiffer() {
        var other = new FacetDefinitionNames(FACET, new RuntimeId("node"),
            new ExecutionTarget("external"), List.of("other"));
        assertThrows(IllegalArgumentException.class,
            () -> catalog(List.of(facet("main", List.of("one")), other)));
    }

    @Test
    void rejectsDuplicateDefinitionNamesWithinAFacet() {
        assertThrows(IllegalArgumentException.class,
            () -> facet("main", List.of("name", "name")));
    }

    @Test
    void acceptsTheSameDefinitionNameInDifferentFacets() {
        var catalog = catalog(List.of(
            facet("first", List.of("same")), facet("second", List.of("same"))));
        assertEquals(List.of(List.of("same"), List.of("same")),
            catalog.facets().stream().map(FacetDefinitionNames::definitionIds).toList());
    }

    @Test
    void rejectsNullCatalogFieldsAndListElements() {
        assertAll(
            () -> assertThrows(NullPointerException.class,
                () -> new PackageDefinitionCatalog(7, null, REVISION, List.of())),
            () -> assertThrows(NullPointerException.class,
                () -> new PackageDefinitionCatalog(7, PLUGIN, REVISION, null)),
            () -> assertThrows(NullPointerException.class,
                () -> catalog(Arrays.asList(facet("main", List.of()), null))));
    }

    @Test
    void rejectsNullFacetFieldsAndDefinitionListElements() {
        assertAll(
            () -> assertThrows(NullPointerException.class,
                () -> new FacetDefinitionNames(null, RUNTIME, TARGET, List.of())),
            () -> assertThrows(NullPointerException.class,
                () -> new FacetDefinitionNames(FACET, null, TARGET, List.of())),
            () -> assertThrows(NullPointerException.class,
                () -> new FacetDefinitionNames(FACET, RUNTIME, null, List.of())),
            () -> assertThrows(NullPointerException.class,
                () -> new FacetDefinitionNames(FACET, RUNTIME, TARGET, null)),
            () -> assertThrows(NullPointerException.class,
                () -> facet("main", Arrays.asList("name", null))));
    }

    @Test
    void rejectsBlankDefinitionNames() {
        for (String name : List.of("", " ", "\t\n", "\u2003")) {
            assertThrows(IllegalArgumentException.class,
                () -> facet("main", List.of(name)), name);
        }
    }

    @Test
    void acceptsZeroAndRejectsNegativeTargetRevision() {
        assertEquals(0,
            new PackageDefinitionCatalog(0, PLUGIN, REVISION, List.of()).targetRevision());
        assertThrows(IllegalArgumentException.class,
            () -> new PackageDefinitionCatalog(-1, PLUGIN, REVISION, List.of()));
    }

    @Test
    void requiresTheSameLowercaseSha256RevisionAsPluginSelection() {
        for (String revision : Arrays.asList(null, "", " ", "A".repeat(64),
            "a".repeat(63), "a".repeat(65), "g".repeat(64))) {
            assertThrows(IllegalArgumentException.class,
                () -> new PluginSelection(PLUGIN, revision, true));
            assertThrows(IllegalArgumentException.class,
                () -> new PackageDefinitionCatalog(7, PLUGIN, revision, List.of()));
        }
        var revision = "0123456789abcdef".repeat(4);
        assertEquals(new PluginSelection(PLUGIN, revision, true).packageRevision(),
            new PackageDefinitionCatalog(7, PLUGIN, revision, List.of()).packageRevision());
    }

    @Test
    void valueRecordsContainOnlyTheApprovedCatalogFields() {
        assertTrue(PackageDefinitionCatalog.class.isRecord());
        assertTrue(FacetDefinitionNames.class.isRecord());
        assertArrayEquals(new String[] {
            "targetRevision", "pluginId", "packageRevision", "facets"},
            Arrays.stream(PackageDefinitionCatalog.class.getRecordComponents())
                .map(component -> component.getName()).toArray(String[]::new));
        assertArrayEquals(new String[] {
            "facetId", "runtimeId", "executionTarget", "definitionIds"},
            Arrays.stream(FacetDefinitionNames.class.getRecordComponents())
                .map(component -> component.getName()).toArray(String[]::new));
    }

    @Test
    void exposesExactlyTheApprovedErrorCodesWithoutAttachingACause() {
        assertEquals(List.of("ENGINE_UNAVAILABLE", "STALE_TARGET", "NOT_SELECTED",
            "PACKAGE_UNAVAILABLE", "UNSUPPORTED_RUNTIME", "READ_FAILED", "CLEANUP_FAILED"),
            Arrays.stream(DefinitionCatalogException.Code.values()).map(Enum::name).toList());
        for (var code : DefinitionCatalogException.Code.values()) {
            var failure = new DefinitionCatalogException(code, "safe diagnostic");
            assertEquals(code, failure.code());
            assertEquals("safe diagnostic", failure.getMessage());
            assertNull(failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
        }
        assertThrows(NullPointerException.class,
            () -> new DefinitionCatalogException(null, "safe diagnostic"));
    }

    @Test
    void exceptionOnlyAddsTheApprovedConstructorAndCodeAccessor() {
        assertTrue(Modifier.isFinal(DefinitionCatalogException.class.getModifiers()));
        assertEquals(RuntimeException.class, DefinitionCatalogException.class.getSuperclass());
        var constructors = DefinitionCatalogException.class.getConstructors();
        assertEquals(1, constructors.length);
        assertArrayEquals(new Class<?>[] {DefinitionCatalogException.Code.class, String.class},
            constructors[0].getParameterTypes());
        var publicMethods = Arrays.stream(DefinitionCatalogException.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers())).toList();
        assertEquals(List.of("code"), publicMethods.stream().map(method -> method.getName()).toList());
    }

    @Test
    void queryProtocolRequiresBothAbstractAsyncOperations() throws Exception {
        assertTrue(RuntimeDefinitionQuery.class.isInterface());
        assertEquals(2, RuntimeDefinitionQuery.class.getDeclaredMethods().length);
        var read = RuntimeDefinitionQuery.class.getMethod("readAsync");
        var close = RuntimeDefinitionQuery.class.getMethod("closeAsync");
        assertAll(
            () -> assertTrue(Modifier.isAbstract(read.getModifiers())),
            () -> assertTrue(Modifier.isAbstract(close.getModifiers())),
            () -> assertFalse(read.isDefault()),
            () -> assertFalse(close.isDefault()),
            () -> assertEquals("reactor.core.publisher.Mono<java.util.List<java.lang.String>>",
                read.getGenericReturnType().getTypeName()),
            () -> assertEquals("reactor.core.publisher.Mono<java.lang.Void>",
                close.getGenericReturnType().getTypeName()));
    }

    private static PackageDefinitionCatalog catalog(List<FacetDefinitionNames> facets) {
        return new PackageDefinitionCatalog(7, PLUGIN, REVISION, facets);
    }

    private static FacetDefinitionNames facet(String id, List<String> definitions) {
        return new FacetDefinitionNames(new FacetId(id), RUNTIME, TARGET, definitions);
    }
}
