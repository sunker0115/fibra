package com.sstlfsj.fibra.runtime.java;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionTest {
    @Test
    void comparesPreReleaseIdentifiersUsingSemanticVersionRules() {
        assertTrue(Version.parse("1.0.0-alpha.2")
            .compareTo(Version.parse("1.0.0-alpha.10")) < 0);
        assertTrue(Version.parse("1.0.0-2")
            .compareTo(Version.parse("1.0.0-beta")) < 0);
        assertTrue(Version.parse("1.0.0-beta")
            .compareTo(Version.parse("1.0.0-beta.1")) < 0);
        assertTrue(Version.parse("1.0.0-rc.1")
            .compareTo(Version.parse("1.0.0")) < 0);
        assertEquals(Version.parse("1.0.0+build.1"),
            Version.parse("1.0.0+build.2"));
    }

    @Test
    void rejectsInvalidSemanticVersions() {
        assertThrows(IllegalArgumentException.class,
            () -> Version.parse("1.0.0-alpha..1"));
        assertThrows(IllegalArgumentException.class,
            () -> Version.parse("1.0.0-01"));
        assertThrows(IllegalArgumentException.class,
            () -> Version.parse("1.0.0+build..1"));
    }
}
