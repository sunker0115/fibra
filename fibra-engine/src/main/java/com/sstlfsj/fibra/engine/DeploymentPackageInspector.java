package com.sstlfsj.fibra.engine;

import org.pf4j.DefaultVersionManager;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

final class DeploymentPackageInspector {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    private static final long MAX_ENTRY = 64L * 1024 * 1024;
    private static final long MAX_TOTAL = 256L * 1024 * 1024;
    private static final int MAX_ENTRIES = 10_000;

    InspectedDeploymentPackage inspect(Path packagePath, Path workspace) {
        var source = packagePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(FibraDeploymentErrorStage.READ, source,
                "deployment package must be a regular file", null);
        }
        try {
            Files.createDirectories(workspace);
            var names = new HashSet<String>();
            long total = 0;
            try (var zip = ZipFile.builder().setPath(source).get()) {
                var entries = zip.getEntries();
                var count = 0;
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (++count > MAX_ENTRIES) {
                        throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                            "deployment package has too many entries", null);
                    }
                    var name = validateName(entry.getName(), entry.isDirectory(), source);
                    if (!names.add(name)) {
                        throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                            "duplicate deployment entry " + name, null);
                    }
                    if (entry.isDirectory()) {
                        validateEntryType(entry, zip, source);
                        continue;
                    }
                    validateEntryType(entry, zip, source);
                    if (entry.getSize() > MAX_ENTRY || entry.getSize() < -1) {
                        throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                            "deployment entry exceeds size limit " + name, null);
                    }
                    var target = workspace.resolve(name).normalize();
                    if (!target.startsWith(workspace)) {
                        throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                            "deployment entry leaves workspace", null);
                    }
                    Files.createDirectories(target.getParent());
                    try (var input = zip.getInputStream(entry);
                         var output = Files.newOutputStream(target)) {
                        total += copyLimited(input, output, MAX_ENTRY);
                    }
                    if (total > MAX_TOTAL) {
                        throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                            "deployment package exceeds total size limit", null);
                    }
                }
            }
            validateTopLevel(names, source);
            var descriptor = readDescriptor(workspace.resolve("deployment.properties"),
                source);
            var checksums = readChecksums(workspace.resolve("checksums.sha256"), source);
            var regular = new HashSet<String>();
            try (var paths = Files.walk(workspace)) {
                paths.filter(Files::isRegularFile).forEach(path -> {
                    var name = workspace.relativize(path).toString().replace('\\', '/');
                    if (!name.equals("checksums.sha256")) {
                        regular.add(name);
                    }
                });
            }
            if (!checksums.keySet().equals(regular)) {
                throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                    "deployment checksum paths do not match package files", null);
            }
            for (var entry : checksums.entrySet()) {
                if (!entry.getValue().equals(sha256(workspace.resolve(entry.getKey())))) {
                    throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                        "deployment checksum mismatch for " + entry.getKey(), null);
                }
            }
            var config = descriptor.configPath();
            if (!checksums.containsKey(config)) {
                throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                    "deployment config is not checksummed", null);
            }
            for (var plugin : descriptor.plugins()) {
                if (!checksums.containsKey(plugin)) {
                    throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                        "deployment plugin is not checksummed " + plugin, null);
                }
            }
            return new InspectedDeploymentPackage(descriptor.id(), descriptor.version(),
                sha256(source), workspace, workspace.resolve(config),
                descriptor.plugins().stream().map(workspace::resolve).toList());
        } catch (FibraDeploymentException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw failure(FibraDeploymentErrorStage.READ, source,
                "cannot read deployment package", failure);
        }
    }

    private static Descriptor readDescriptor(Path path, Path source) throws IOException {
        var properties = new Properties();
        try (var input = Files.newInputStream(path)) {
            properties.load(input);
        }
        var allowed = new HashSet<>(Set.of("deployment.id", "deployment.version",
            "config.path"));
        var plugins = new ArrayList<String>();
        for (int index = 0; ; index++) {
            var key = "plugin." + index;
            var value = properties.getProperty(key);
            if (value == null) {
                break;
            }
            allowed.add(key);
            plugins.add(validateRelative(value, "plugins/", source));
        }
        if (!properties.stringPropertyNames().equals(allowed)) {
            throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                "deployment.properties contains missing or unknown keys", null);
        }
        var id = properties.getProperty("deployment.id");
        var version = properties.getProperty("deployment.version");
        if (id == null || !ID.matcher(id).matches()) {
            throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                "invalid deployment.id", null);
        }
        if (!validVersion(version)) {
            throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                "invalid deployment.version", null);
        }
        var config = validateRelative(properties.getProperty("config.path"), "config/",
            source);
        var sorted = plugins.stream().sorted().toList();
        if (!plugins.equals(sorted) || plugins.size() != new HashSet<>(plugins).size()) {
            throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                "deployment plugins must be unique and sorted", null);
        }
        return new Descriptor(id, version, config, List.copyOf(plugins));
    }

    private static boolean validVersion(String version) {
        if (version == null) {
            return false;
        }
        try {
            return new DefaultVersionManager().checkVersionConstraint(version,
                ">=" + version + " & <=" + version);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static java.util.Map<String, String> readChecksums(Path path, Path source)
        throws IOException {
        var result = new HashMap<String, String>();
        for (var line : Files.readAllLines(path)) {
            if (line.length() < 67 || !line.substring(64, 66).equals("  ")) {
                throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                    "invalid checksums.sha256 line", null);
            }
            var digest = line.substring(0, 64);
            var name = validateName(line.substring(66), false, source);
            if (!SHA.matcher(digest).matches() || result.putIfAbsent(name, digest) != null) {
                throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                    "invalid or duplicate checksum", null);
            }
        }
        return java.util.Map.copyOf(result);
    }

    private static void validateTopLevel(Set<String> names, Path source) {
        if (!names.contains("deployment.properties") || !names.contains("checksums.sha256")) {
            throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                "deployment metadata is missing", null);
        }
        var unknown = names.stream().filter(name -> !name.equals("deployment.properties")
            && !name.equals("checksums.sha256") && !name.startsWith("plugins/")
            && !name.startsWith("config/")).findFirst();
        if (unknown.isPresent()) {
            throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                "unknown deployment entry " + unknown.orElseThrow(), null);
        }
    }

    private static void validateEntryType(ZipArchiveEntry entry, ZipFile zip,
                                          Path source) {
        if (entry.isUnixSymlink()) {
            throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                "deployment package must not contain symbolic links", null);
        }
        var fileType = entry.getUnixMode() & UnixStat.FILE_TYPE_FLAG;
        if (entry.isDirectory()) {
            if (fileType != 0 && fileType != UnixStat.DIR_FLAG) {
                throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                    "deployment directory entry has a non-directory type", null);
            }
        } else if (fileType != 0 && fileType != UnixStat.FILE_FLAG) {
            throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                "deployment file entry has a non-regular type", null);
        }
        if (!zip.canReadEntryData(entry)) {
            throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                "deployment package contains an unreadable entry", null);
        }
    }

    private static String validateRelative(String value, String prefix, Path source) {
        if (value == null) {
            throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                "missing deployment path", null);
        }
        var name = validateName(value, false, source);
        if (!name.startsWith(prefix)) {
            throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                "deployment path must start with " + prefix, null);
        }
        return name;
    }

    private static String validateName(String name, boolean directory, Path source) {
        if (name.isBlank() || name.startsWith("/") || name.contains("\\")
            || name.matches("[A-Za-z]:.*")) {
            throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                "invalid deployment entry path " + name, null);
        }
        var pathName = directory && name.endsWith("/")
            ? name.substring(0, name.length() - 1) : name;
        if (pathName.isBlank() || directory && !name.endsWith("/")) {
            throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                "invalid deployment directory path " + name, null);
        }
        var normalized = Path.of(pathName).normalize().toString().replace('\\', '/');
        if (!normalized.equals(pathName) || normalized.startsWith("../")
            || normalized.equals("..")) {
            throw failure(FibraDeploymentErrorStage.VALIDATE, source,
                "deployment entry path is not normalized " + name, null);
        }
        return directory ? pathName + "/" : pathName;
    }

    private static long copyLimited(InputStream input, java.io.OutputStream output,
                                    long limit) throws IOException {
        var buffer = new byte[8192];
        long count = 0;
        for (int read; (read = input.read(buffer)) >= 0; ) {
            count += read;
            if (count > limit) {
                throw new IOException("deployment entry exceeds size limit");
            }
            output.write(buffer, 0, read);
        }
        return count;
    }

    private static String sha256(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                var buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0; ) {
                    digest.update(buffer, 0, read);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static FibraDeploymentException failure(FibraDeploymentErrorStage stage,
                                                     Path path, String message,
                                                     Throwable cause) {
        return new FibraDeploymentException(stage, path, message, cause);
    }

    private record Descriptor(String id, String version, String configPath,
                              List<String> plugins) { }
}
