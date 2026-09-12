package com.sstlfsj.fibra.plugins.subprocess.local;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowsExecutableResolverTest {
    @Test void targetCwdShadowsPathAndComPrecedesExe() throws Exception {
        var files = new FakeFiles("C:\\target\\tool.com", "C:\\target\\tool.exe",
            "C:\\bin\\tool.com");
        var resolver = resolver(files, Map.of("Path", "C:\\bin"));

        assertEquals("C:\\target\\tool.com", resolver.resolve("tool", "C:\\target"));
        assertEquals(java.util.List.of("C:\\target\\tool.com"), files.probes);
    }

    @Test void pathDirectoryOrderPrecedesExtensionPreferenceInLaterDirectories() throws Exception {
        var files = new FakeFiles("C:\\first\\tool.exe", "C:\\second\\tool.com");
        var resolver = resolver(files, Map.of("PATH", "C:\\first;C:\\second"));

        assertEquals("C:\\first\\tool.exe", resolver.resolve("tool", "C:\\target"));
        assertEquals(java.util.List.of("C:\\target\\tool.com", "C:\\target\\tool.exe",
            "C:\\first\\tool.com", "C:\\first\\tool.exe"), files.probes);
    }

    @Test void explicitRelativeAndAbsolutePathsDoNotSearchPath() throws Exception {
        var relativeFiles = new FakeFiles("C:\\target\\tools\\tool.exe");
        var relative = resolver(relativeFiles, Map.of("PATH", "C:\\other"));
        assertEquals("C:\\target\\tools\\tool.exe",
            relative.resolve("tools\\tool", "C:\\target"));
        assertEquals(java.util.List.of("C:\\target\\tools\\tool.com",
            "C:\\target\\tools\\tool.exe"), relativeFiles.probes);

        var absoluteFiles = new FakeFiles("D:\\apps\\tool.exe");
        var absolute = resolver(absoluteFiles, Map.of("PATH", "C:\\other"));
        assertEquals("D:\\apps\\tool.exe", absolute.resolve("D:\\apps\\tool.exe", "C:\\target"));
        assertEquals(java.util.List.of("D:\\apps\\tool.exe"), absoluteFiles.probes);
    }

    private static WindowsExecutableResolver resolver(FakeFiles files, Map<String, String> environment) {
        return new WindowsExecutableResolver(environment, Map.of(), files);
    }

    private static final class FakeFiles implements WindowsExecutableResolver.CandidateAccess {
        private final Set<String> available;
        private final ArrayList<String> probes = new ArrayList<>();

        private FakeFiles(String... available) {
            this.available = new LinkedHashSet<>(java.util.List.of(available));
        }

        @Override public boolean exists(String candidate) {
            probes.add(candidate);
            return available.contains(candidate);
        }

        @Override public String absolute(String candidate) {
            return candidate;
        }
    }
}
