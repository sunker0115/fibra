package com.sstlfsj.fibra.loader.pf4j;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.pf4j.DefaultVersionManager;
import org.pf4j.PropertiesPluginDescriptorFinder;
import org.pf4j.processor.ExtensionStorage;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

final class PluginPackageInspector {
    private static final Pattern PLUGIN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final String EXTENSION_INDEX = "META-INF/extensions.idx";
    private static final Set<String> DESCRIPTOR_KEYS = Set.of(
        "plugin.id",
        "plugin.version",
        "plugin.dependencies",
        "plugin.description",
        "plugin.provider",
        "plugin.license"
    );
    private static final List<String> SHARED_RUNTIME_PREFIXES = List.of(
        "com/sstlfsj/fibra/",
        "org/pf4j/",
        "org/reactivestreams/",
        "reactor/",
        "org/slf4j/"
    );
    private static final DefaultVersionManager VERSIONS = new DefaultVersionManager();

    InspectedPluginPackage inspectCandidate(Path candidateZip, Path workRoot) {
        Objects.requireNonNull(candidateZip, "candidateZip");
        Objects.requireNonNull(workRoot, "workRoot");
        var candidate = candidateZip.toAbsolutePath().normalize();
        var normalizedWorkRoot = workRoot.toAbsolutePath().normalize();
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new FibraArtifactException(
                FibraArtifactErrorStage.READ,
                List.of(candidate),
                List.of(),
                "candidate must be a regular ZIP file",
                null
            );
        }
        try {
            var inputRoot = Files.createDirectories(normalizedWorkRoot.resolve("input"));
            var nextRoot = Files.createDirectories(normalizedWorkRoot.resolve("next"));
            var internalZip = Files.createTempFile(inputRoot, "candidate-", ".zip");
            Files.copy(candidate, internalZip, StandardCopyOption.REPLACE_EXISTING);
            var topLevel = extractCandidate(internalZip, nextRoot, candidate);
            try {
                return inspectDirectory(nextRoot.resolve(topLevel));
            } catch (FibraArtifactException exception) {
                throw repackage(candidate, exception);
            }
        } catch (FibraArtifactException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new FibraArtifactException(
                FibraArtifactErrorStage.READ,
                List.of(candidate),
                List.of(),
                "cannot read candidate ZIP " + candidate,
                exception
            );
        }
    }

    InspectedPluginPackage inspectDirectory(Path packageRoot) {
        Objects.requireNonNull(packageRoot, "packageRoot");
        var requestedRoot = packageRoot.toAbsolutePath().normalize();
        try {
            if (!Files.isDirectory(requestedRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid(requestedRoot, List.of(), "plugin package must be a directory");
            }
            var normalizedRoot = requestedRoot.toRealPath();
            var descriptorPath = normalizedRoot.resolve("plugin.properties");
            if (!Files.isRegularFile(descriptorPath, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid(normalizedRoot, List.of(),
                    "plugin package must contain plugin.properties");
            }
            var descriptor = new PropertiesPluginDescriptorFinder().find(normalizedRoot);
            var pluginId = descriptor.getPluginId();
            var version = descriptor.getVersion();
            validateIdentity(normalizedRoot, pluginId, version);
            validateProperties(normalizedRoot, descriptorPath, pluginId);

            var lib = normalizedRoot.resolve("lib");
            if (!Files.isDirectory(lib, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid(normalizedRoot, List.of(pluginId),
                    "plugin package must contain a lib directory");
            }
            var classpath = listClasspath(normalizedRoot, lib, pluginId);
            var expectedMainJar = lib.resolve(pluginId + "-" + version + ".jar");
            if (!Files.isRegularFile(expectedMainJar, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid(normalizedRoot, List.of(pluginId),
                    "plugin package must contain exactly one main JAR");
            }
            var mainJar = expectedMainJar.toRealPath();
            for (var jar : classpath) {
                validateJarContents(normalizedRoot, pluginId, jar);
            }

            return new InspectedPluginPackage(
                normalizedRoot,
                descriptor,
                mainJar,
                classpath,
                digest(normalizedRoot, descriptorPath, classpath),
                readEntrypointClassNames(mainJar)
            );
        } catch (FibraArtifactException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new FibraArtifactException(
                FibraArtifactErrorStage.READ,
                List.of(requestedRoot),
                List.of(),
                "cannot read plugin package " + requestedRoot,
                exception
            );
        }
    }

    private static void validateProperties(Path packageRoot, Path descriptorPath,
                                           String pluginId) throws IOException {
        var properties = new Properties();
        try (var input = Files.newInputStream(descriptorPath)) {
            properties.load(input);
        }
        for (var key : properties.stringPropertyNames()) {
            if (!DESCRIPTOR_KEYS.contains(key)
                && !key.equals("plugin.class")
                && !key.equals("plugin.requires")) {
                throw invalid(packageRoot, List.of(pluginId),
                    "unsupported plugin descriptor property " + key);
            }
        }
        if (!properties.getProperty("plugin.class", "").isBlank()) {
            throw invalid(packageRoot, List.of(pluginId), "plugin.class must be absent or empty");
        }
        if (properties.containsKey("plugin.requires")) {
            throw invalid(packageRoot, List.of(pluginId), "plugin.requires is not supported");
        }
    }

    private static String extractCandidate(Path internalZip, Path nextRoot, Path candidate)
        throws IOException {
        var topLevels = new LinkedHashSet<String>();
        var names = new LinkedHashSet<String>();
        try (var zip = ZipFile.builder().setPath(internalZip).get()) {
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var parts = validateZipEntry(entry, zip, candidate, names);
                topLevels.add(parts.getFirst());
                if (topLevels.size() > 1) {
                    throw candidateInvalid(candidate,
                        "candidate ZIP must contain exactly one top-level directory");
                }
                extractEntry(zip, entry, nextRoot, candidate);
            }
        }
        if (topLevels.size() != 1) {
            throw candidateInvalid(candidate,
                "candidate ZIP must contain exactly one top-level directory");
        }
        return topLevels.getFirst();
    }

    private static List<String> validateZipEntry(ZipArchiveEntry entry, ZipFile zip,
                                                  Path candidate, Set<String> names) {
        var name = entry.getName();
        if (name == null || name.isEmpty() || name.indexOf('\\') >= 0
            || name.startsWith("/") || name.matches("[A-Za-z]:.*")) {
            throw candidateInvalid(candidate, "candidate ZIP contains an absolute path");
        }
        var protocolName = entry.isDirectory() && name.endsWith("/")
            ? name.substring(0, name.length() - 1)
            : name;
        var parts = List.of(protocolName.split("/", -1));
        if (parts.stream().anyMatch(part -> part.isEmpty()
            || part.equals(".") || part.equals(".."))) {
            throw candidateInvalid(candidate, "candidate ZIP contains an unsafe path");
        }
        if (!names.add(protocolName)) {
            throw candidateInvalid(candidate, "candidate ZIP contains a duplicate entry");
        }
        validateZipLayout(entry, parts, candidate);
        validateZipEntryType(entry, zip, candidate);
        return parts;
    }

    private static void validateZipLayout(ZipArchiveEntry entry, List<String> parts,
                                          Path candidate) {
        var standard = switch (parts.size()) {
            case 1 -> entry.isDirectory();
            case 2 -> (parts.get(1).equals("plugin.properties") && !entry.isDirectory())
                || (parts.get(1).equals("lib") && entry.isDirectory());
            case 3 -> parts.get(1).equals("lib")
                && parts.get(2).endsWith(".jar")
                && !entry.isDirectory();
            default -> false;
        };
        if (!standard) {
            throw candidateInvalid(candidate, "candidate ZIP contains a nonstandard layer");
        }
    }

    private static void validateZipEntryType(ZipArchiveEntry entry, ZipFile zip,
                                             Path candidate) {
        if (entry.isUnixSymlink()) {
            throw candidateInvalid(candidate, "candidate ZIP must not contain symbolic links");
        }
        var fileType = entry.getUnixMode() & UnixStat.FILE_TYPE_FLAG;
        if (entry.isDirectory()) {
            if (fileType != 0 && fileType != UnixStat.DIR_FLAG) {
                throw candidateInvalid(candidate,
                    "candidate ZIP directory entry has a non-directory type");
            }
        } else if (fileType != 0 && fileType != UnixStat.FILE_FLAG) {
            throw candidateInvalid(candidate,
                "candidate ZIP file entry has a non-regular type");
        }
        if (!zip.canReadEntryData(entry)) {
            throw candidateInvalid(candidate, "candidate ZIP contains an unreadable entry");
        }
    }

    private static void extractEntry(ZipFile zip, ZipArchiveEntry entry, Path nextRoot,
                                     Path candidate) throws IOException {
        var target = nextRoot.resolve(entry.getName()).normalize();
        if (!target.startsWith(nextRoot)) {
            throw candidateInvalid(candidate, "candidate ZIP entry escapes the work directory");
        }
        if (entry.isDirectory()) {
            Files.createDirectories(target);
            return;
        }
        Files.createDirectories(target.getParent());
        try (var input = zip.getInputStream(entry)) {
            Files.copy(input, target);
        }
    }

    private static void validateIdentity(Path packageRoot, String pluginId, String version) {
        if (pluginId == null || !PLUGIN_ID.matcher(pluginId).matches()) {
            throw invalid(packageRoot, List.of(), "invalid plugin.id");
        }
        if (!packageRoot.getFileName().toString().equals(pluginId)) {
            throw invalid(packageRoot, List.of(pluginId),
                "plugin directory name must equal plugin.id");
        }
        if (version == null || version.isBlank()) {
            throw invalid(packageRoot, List.of(pluginId), "plugin.version is required");
        }
        try {
            VERSIONS.compareVersions(version, version);
        } catch (RuntimeException exception) {
            throw new FibraArtifactException(
                FibraArtifactErrorStage.VALIDATE,
                List.of(packageRoot),
                List.of(pluginId),
                "plugin.version must be valid SemVer",
                exception
            );
        }
    }

    private static List<Path> listClasspath(Path packageRoot, Path lib, String pluginId)
        throws IOException {
        var classpath = new ArrayList<Path>();
        try (var children = Files.list(lib)) {
            for (var child : children.sorted().toList()) {
                if (!Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)
                    || !child.getFileName().toString().endsWith(".jar")) {
                    throw invalid(packageRoot, List.of(pluginId),
                        "lib direct children must be regular JAR files");
                }
                classpath.add(child.toRealPath());
            }
        }
        return List.copyOf(classpath);
    }

    private static void validateJarContents(Path packageRoot, String pluginId, Path jar)
        throws IOException {
        try (var jarFile = new JarFile(jar.toFile())) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                var name = entries.nextElement().getName();
                for (var prefix : SHARED_RUNTIME_PREFIXES) {
                    if (name.startsWith(prefix)) {
                        throw invalid(packageRoot, List.of(pluginId),
                            "plugin package must not bundle shared runtime class " + name);
                    }
                }
            }
        }
    }

    private static List<String> readEntrypointClassNames(Path mainJar) throws IOException {
        try (var jar = new JarFile(mainJar.toFile())) {
            var index = jar.getJarEntry(EXTENSION_INDEX);
            if (index == null) {
                return List.of();
            }
            var classNames = new LinkedHashSet<String>();
            try (var reader = new InputStreamReader(jar.getInputStream(index),
                StandardCharsets.UTF_8)) {
                ExtensionStorage.read(reader, classNames);
            }
            return List.copyOf(classNames);
        }
    }

    private static String digest(Path packageRoot, Path descriptorPath, List<Path> classpath)
        throws IOException {
        var digest = sha256();
        updateDigest(digest, packageRoot, descriptorPath);
        for (var jar : classpath) {
            updateDigest(digest, packageRoot, jar);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void updateDigest(MessageDigest digest, Path packageRoot, Path file)
        throws IOException {
        var relativePath = packageRoot.relativize(file).toString().replace('\\', '/');
        var pathBytes = relativePath.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(pathBytes.length).array());
        digest.update(pathBytes);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(Files.size(file)).array());
        try (var input = Files.newInputStream(file)) {
            var buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static FibraArtifactException invalid(Path packageRoot, List<String> artifactIds,
                                                   String message) {
        return new FibraArtifactException(
            FibraArtifactErrorStage.VALIDATE,
            List.of(packageRoot),
            artifactIds,
            message,
            null
        );
    }

    private static FibraArtifactException candidateInvalid(Path candidate, String message) {
        return new FibraArtifactException(
            FibraArtifactErrorStage.VALIDATE,
            List.of(candidate),
            List.of(),
            message,
            null
        );
    }

    private static FibraArtifactException repackage(Path candidate,
                                                    FibraArtifactException exception) {
        return new FibraArtifactException(
            exception.stage(),
            List.of(candidate),
            exception.artifactIds(),
            exception.getMessage(),
            exception
        );
    }
}
