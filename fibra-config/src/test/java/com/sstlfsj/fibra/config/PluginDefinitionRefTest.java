package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDefinitionRefTest {
    @Test
    void requiresTheCompletePluginFacetAndDefinitionIdentity() {
        var reference = new PluginDefinitionRef("plugin", "facet", "definition");

        assertEquals("plugin", reference.pluginId());
        assertEquals("facet", reference.facetId());
        assertEquals("definition", reference.definitionId());
        assertThrows(IllegalArgumentException.class,
            () -> new PluginDefinitionRef("plugin", " ", "definition"));
    }

    @Test
    void desiredEntryKeepsTheCompleteReferenceAcrossBuilderAndValueOperations() {
        var reference = new PluginDefinitionRef("plugin", "facet", "definition");
        var entry = DesiredInputEntry.builder("entry", reference)
            .config(LiteralValue.of("config")).build();
        var copied = entry.toBuilder().build();

        assertEquals(reference, entry.definitionRef());
        assertEquals(entry, copied);
        assertEquals(entry.hashCode(), copied.hashCode());
        assertTrue(entry.toString().contains("definitionRef=" + reference));
        assertThrows(NullPointerException.class, () -> DesiredInputEntry.builder("entry", null).build());
    }
}
