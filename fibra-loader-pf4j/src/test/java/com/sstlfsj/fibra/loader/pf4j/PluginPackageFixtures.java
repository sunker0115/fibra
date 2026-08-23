package com.sstlfsj.fibra.loader.pf4j;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

final class PluginPackageFixtures {
    private PluginPackageFixtures() {
    }

    static Path standardDirectory(Path packagesRoot, String id, String version)
        throws IOException {
        var packageRoot = Files.createDirectory(packagesRoot.resolve(id));
        writeProperties(packageRoot, Map.of(
            "plugin.id", id,
            "plugin.version", version
        ));
        var lib = Files.createDirectory(packageRoot.resolve("lib"));
        writeJar(lib.resolve(id + "-" + version + ".jar"), Map.of());
        return packageRoot;
    }

    static void writeProperties(Path packageRoot, Map<String, String> values)
        throws IOException {
        var text = values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .reduce("", (left, right) -> left + right + '\n');
        Files.writeString(packageRoot.resolve("plugin.properties"), text,
            StandardCharsets.ISO_8859_1);
    }

    static void writeJar(Path jar, Map<String, byte[]> entries) throws IOException {
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            writeJarEntries(output, entries);
        }
    }

    static void writeJar(Path jar, Manifest manifest, Map<String, byte[]> entries)
        throws IOException {
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            writeJarEntries(output, entries);
        }
    }

    private static void writeJarEntries(JarOutputStream output, Map<String, byte[]> entries)
        throws IOException {
        for (var entry : entries.entrySet()) {
            output.putNextEntry(new JarEntry(entry.getKey()));
            output.write(entry.getValue());
            output.closeEntry();
        }
    }

    static Map<String, byte[]> entries(String name, String text) {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put(name, text.getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    static byte[] extensionIndex(List<String> classNames) {
        return (String.join("\n", classNames) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    static Path zipDirectory(Path packageRoot, Path zip) throws IOException {
        var topLevel = packageRoot.getFileName().toString();
        try (var output = new ZipArchiveOutputStream(zip)) {
            try (var files = Files.walk(packageRoot)) {
                for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
                    var name = topLevel + "/"
                        + packageRoot.relativize(file).toString().replace('\\', '/');
                    var entry = new ZipArchiveEntry(name);
                    output.putArchiveEntry(entry);
                    Files.copy(file, output);
                    output.closeArchiveEntry();
                }
            }
        }
        return zip;
    }

    static Path writeZip(Path zip, Map<String, byte[]> entries) throws IOException {
        try (var output = new ZipArchiveOutputStream(zip)) {
            for (var item : entries.entrySet()) {
                var entry = new ZipArchiveEntry(item.getKey());
                output.putArchiveEntry(entry);
                output.write(item.getValue());
                output.closeArchiveEntry();
            }
        }
        return zip;
    }

    static Path writeSymlinkZip(Path zip, String name, String target) throws IOException {
        try (var output = new ZipArchiveOutputStream(zip)) {
            var entry = new ZipArchiveEntry(name);
            entry.setUnixMode(UnixStat.LINK_FLAG | UnixStat.DEFAULT_LINK_PERM);
            output.putArchiveEntry(entry);
            output.write(target.getBytes(StandardCharsets.UTF_8));
            output.closeArchiveEntry();
        }
        return zip;
    }
}
