package com.sstlfsj.fibra.plugins.fs.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchCommandsTest {
    @Test
    void globUsesFixedDirectArgvAndExcludesVcsMetadata() {
        assertEquals(List.of(
            "--files", "--glob=*.java", "--sort=modified", "--no-ignore", "--hidden",
            "--glob=!**/.git", "--glob=!**/.git/**",
            "--glob=!**/.svn", "--glob=!**/.svn/**",
            "--glob=!**/.hg", "--glob=!**/.hg/**",
            "--glob=!**/.bzr", "--glob=!**/.bzr/**",
            "--glob=!**/.jj", "--glob=!**/.jj/**",
            "--glob=!**/.sl", "--glob=!**/.sl/**",
            "--", "src"), SearchCommands.glob("*.java", "src"));
    }

    @Test
    void grepKeepsPatternAndTargetAsSingleArgvElements() {
        assertEquals(List.of("--json", "--regexp=-unsafe;echo", "--glob=*.{java,kt}",
            "--", "-target"), SearchCommands.grep("-unsafe;echo", "-target",
            "*.{java,kt}"));
    }

    @Test
    void validationRejectsBlankOrMultipleFilters() {
        assertThrows(IllegalArgumentException.class,
            () -> SearchCommands.glob(" ", null));
        assertThrows(IllegalArgumentException.class,
            () -> SearchCommands.grep("", null, null));
        assertThrows(IllegalArgumentException.class,
            () -> SearchCommands.grep("x", null, "*.java,*.kt"));
        assertThrows(IllegalArgumentException.class,
            () -> SearchCommands.grep("x", null, "!secret/**"));
    }
}
