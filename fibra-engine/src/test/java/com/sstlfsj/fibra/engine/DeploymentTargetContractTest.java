package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentTargetContractTest {
    private static final String A_REVISION = "a".repeat(64);
    private static final String B_REVISION = "b".repeat(64);

    @Test
    void digestDependsOnlyOnCanonicalSelectionsAndDesiredContent() {
        var desired = new DesiredInputGraph(List.of(
            DesiredInputEntry.builder("entry", definition("definition")).build()));
        var first = DeploymentTarget.of(7, List.of(
            selection("second", B_REVISION, false),
            selection("first", A_REVISION, true)), desired, ConfigContextSnapshot.empty());
        var sameContent = DeploymentTarget.of(19, List.of(
            selection("first", A_REVISION, true),
            selection("second", B_REVISION, false)), desired, ConfigContextSnapshot.empty());

        assertEquals(first.targetDigest(), sameContent.targetDigest());
        assertNotEquals(first.targetRevision(), sameContent.targetRevision());
        assertEquals(first.targetDigest(), DeploymentTarget.digestOf(
            sameContent.selections().values(), desired, ConfigContextSnapshot.empty()));

        var changedGate = DeploymentTarget.of(20, List.of(
            selection("first", A_REVISION, false),
            selection("second", B_REVISION, false)), desired, ConfigContextSnapshot.empty());
        var changedDesired = DeploymentTarget.of(21, first.selections().values(),
            new DesiredInputGraph(List.of(
                DesiredInputEntry.builder("entry", definition("other-definition")).build())),
            ConfigContextSnapshot.empty());
        assertNotEquals(first.targetDigest(), changedGate.targetDigest());
        assertNotEquals(first.targetDigest(), changedDesired.targetDigest());
    }

    @Test
    void continuousSameContentIsNoOpButAtoBtoAAllocatesANewGeneration() {
        var desired = new DesiredInputGraph(List.of());
        var firstA = DeploymentTarget.of(1,
            List.of(selection("plugin", A_REVISION, true)), desired,
            ConfigContextSnapshot.empty());

        assertTrue(firstA.hasSameContent(List.of(
            selection("plugin", A_REVISION, true)), desired,
            ConfigContextSnapshot.empty()));

        var targetB = DeploymentTarget.of(2,
            List.of(selection("plugin", B_REVISION, true)), desired,
            ConfigContextSnapshot.empty());
        var secondA = DeploymentTarget.of(3,
            List.of(selection("plugin", A_REVISION, true)), desired,
            ConfigContextSnapshot.empty());
        assertNotEquals(firstA.targetDigest(), targetB.targetDigest());
        assertEquals(firstA.targetDigest(), secondA.targetDigest());
        assertNotEquals(firstA.targetRevision(), secondA.targetRevision());
    }

    @Test
    void targetRequiresPositiveGenerationAndOneSelectionPerPlugin() {
        var desired = new DesiredInputGraph(List.of());
        assertThrows(IllegalArgumentException.class, () -> DeploymentTarget.of(0,
            List.of(selection("plugin", A_REVISION, true)), desired,
            ConfigContextSnapshot.empty()));
        assertThrows(IllegalArgumentException.class, () -> DeploymentTarget.of(1,
            List.of(selection("plugin", A_REVISION, true),
                selection("plugin", B_REVISION, true)), desired,
            ConfigContextSnapshot.empty()));
        assertThrows(IllegalArgumentException.class,
            () -> selection("plugin", "not-a-digest", true));
    }

    @Test
    void selectionSnapshotIsDefensiveAndDesiredRootOrderRemainsMeaningful() {
        var input = new ArrayList<PluginSelection>();
        input.add(selection("plugin", A_REVISION, true));
        var target = DeploymentTarget.of(1, input,
            new DesiredInputGraph(List.of(
                DesiredInputEntry.builder("first", definition("one")).build(),
                DesiredInputEntry.builder("second", definition("two")).build())),
            ConfigContextSnapshot.empty());
        input.clear();

        assertEquals(1, target.selections().size());
        assertThrows(UnsupportedOperationException.class,
            () -> target.selections().clear());
        var reversed = DeploymentTarget.of(2, target.selections().values(),
            new DesiredInputGraph(List.of(
                DesiredInputEntry.builder("second", definition("two")).build(),
                DesiredInputEntry.builder("first", definition("one")).build())),
            ConfigContextSnapshot.empty());
        assertNotEquals(target.targetDigest(), reversed.targetDigest());
    }

    private static PluginSelection selection(String id, String revision, boolean enabled) {
        return new PluginSelection(new PluginId(id), revision, enabled);
    }

    private static PluginDefinitionRef definition(String definitionId) {
        return new PluginDefinitionRef("plugin", "main", definitionId);
    }
}
