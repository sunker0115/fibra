package com.sstlfsj.fibra.artifact;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 不可变的逻辑插件安装单元；所有 facet 内容身份均由受控文件树计算。 */
public final class PluginPackage {
    public static final String MANIFEST = "fibra-package.yaml";

    private static final Set<String> PACKAGE_FIELDS = Set.of(
        "format", "id", "version", "facets");
    private static final Set<String> FACET_FIELDS = Set.of(
        "id", "role", "runtime", "target", "payload", "dependencies", "capabilities");
    private static final Set<String> DEPENDENCY_FIELDS = Set.of("pluginId", "facetId");
    private static final byte[] DIGEST_PREFIX =
        "fibra-content-v1\0".getBytes(StandardCharsets.UTF_8);
    private static final YAMLMapper YAML = YAMLMapper.builder(YAMLFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(16).maxStringLength(4096)
                .maxDocumentLength(64 * 1024).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    private final Path root;
    private final PluginId pluginId;
    private final String version;
    private final String packageDigest;
    private final List<PluginFacet> facets;

    private PluginPackage(Path root, PluginId pluginId, String version,
                          String packageDigest, List<PluginFacet> facets) {
        this.root = root;
        this.pluginId = pluginId;
        this.version = version;
        this.packageDigest = packageDigest;
        this.facets = List.copyOf(facets);
    }

    public Path root() { return root; }
    public PluginId pluginId() { return pluginId; }
    public String version() { return version; }
    public String packageDigest() { return packageDigest; }
    public List<PluginFacet> facets() { return facets; }

    /** 只读取新格式根清单；不探测或迁移旧 plugin.properties。 */
    public static PluginPackage read(Path source) {
        return read(source, () -> { });
    }

    static PluginPackage read(Path source, Runnable betweenSnapshots) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(betweenSnapshots, "betweenSnapshots");
        var normalized = source.toAbsolutePath().normalize();
        try {
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                    "plugin package must be a directory, not a symbolic link");
            }
            var root = normalized.toRealPath();
            var first = snapshot(root);
            betweenSnapshots.run();
            Snapshot second;
            try {
                second = snapshot(root);
            } catch (IOException | RuntimeException failure) {
                throw digestFailure(root,
                    "plugin package changed while computing its content identity", failure);
            }
            if (!first.sameContent(second)) {
                throw digestFailure(root,
                    "plugin package changed while computing its content identity", null);
            }
            return second.pluginPackage();
        } catch (ArtifactException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new ArtifactException(ArtifactPhase.VALIDATE,
                "invalid plugin package: " + failure.getMessage(), normalized, failure);
        }
    }

    private static Snapshot snapshot(Path root) throws IOException {
        validateTree(root);
        var manifest = root.resolve(MANIFEST);
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                "plugin package requires a regular " + MANIFEST);
        }
        if (Files.size(manifest) > 64 * 1024) {
            throw new IllegalArgumentException(
                "plugin package manifest exceeds 64 KiB");
        }
        var manifestBytes = Files.readAllBytes(manifest);
        var raw = YAML.readValue(manifestBytes, Object.class);
        var values = exactMap(raw, PACKAGE_FIELDS, "plugin package");
        if (!(values.get("format") instanceof Integer format) || format != 1) {
            throw new IllegalArgumentException(
                "plugin package format must be integer 1");
        }
        var pluginId = new PluginId(text(values.get("id"), "id"));
        var version = text(values.get("version"), "version");
        var facets = facets(values.get("facets"), root);
        return new Snapshot(new PluginPackage(root, pluginId, version, digest(root), facets),
            manifestBytes);
    }

    private static List<PluginFacet> facets(Object raw, Path root) throws IOException {
        if (!(raw instanceof List<?> entries) || entries.isEmpty()) {
            throw new IllegalArgumentException("facets must be a non-empty array");
        }
        var result = new ArrayList<PluginFacet>();
        var ids = new LinkedHashSet<FacetId>();
        for (var entry : entries) {
            var values = exactMap(entry, FACET_FIELDS, "facet");
            var id = new FacetId(text(values.get("id"), "facets.id"));
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate facet id: " + id);
            }
            var payload = payload(root, text(values.get("payload"), "facets.payload"));
            result.add(new PluginFacet(id,
                new FacetRole(text(values.get("role"), "facets.role")),
                new RuntimeId(text(values.get("runtime"), "facets.runtime")),
                new ExecutionTarget(text(values.get("target"), "facets.target")),
                payload, digest(payload), dependencies(values.get("dependencies")),
                capabilities(values.get("capabilities"))));
        }
        return List.copyOf(result);
    }

    private static List<FacetDependency> dependencies(Object raw) {
        if (!(raw instanceof List<?> entries)) {
            throw new IllegalArgumentException("dependencies must be an array");
        }
        var result = new ArrayList<FacetDependency>();
        for (var entry : entries) {
            var values = exactMap(entry, DEPENDENCY_FIELDS, "facet dependency");
            result.add(new FacetDependency(
                new PluginId(text(values.get("pluginId"), "dependencies.pluginId")),
                new FacetId(text(values.get("facetId"), "dependencies.facetId"))));
        }
        return List.copyOf(result);
    }

    private static List<String> capabilities(Object raw) {
        if (!(raw instanceof List<?> entries)) {
            throw new IllegalArgumentException("capabilities must be an array");
        }
        var result = new LinkedHashSet<String>();
        for (var entry : entries) {
            var capability = text(entry, "capabilities");
            if (!result.add(capability)) {
                throw new IllegalArgumentException(
                    "duplicate required capability: " + capability);
            }
        }
        return List.copyOf(result);
    }

    private static Path payload(Path root, String value) throws IOException {
        if (value.indexOf('\\') >= 0 || value.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException(
                "facet payload must use a relative package path");
        }
        var relative = Path.of(value);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException(
                "facet payload must use a relative package path");
        }
        for (var part : relative) {
            if (part.toString().equals(".") || part.toString().equals("..")) {
                throw new IllegalArgumentException(
                    "facet payload path must not contain dot segments");
            }
        }
        var candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root) || candidate.equals(root)
            || candidate.equals(root.resolve(MANIFEST))) {
            throw new IllegalArgumentException(
                "facet payload must be content inside its package root");
        }
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
            && !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                "facet payload must be an existing file or directory");
        }
        var real = candidate.toRealPath();
        if (!real.startsWith(root) || real.equals(root)) {
            throw new IllegalArgumentException(
                "facet payload must resolve inside its package root");
        }
        return real;
    }

    private static Map<String, Object> exactMap(Object raw, Set<String> fields,
                                                 String name) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        var result = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(name + " keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        var unknown = result.keySet().stream()
            .filter(key -> !fields.contains(key)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                "unknown " + name + " fields: " + unknown);
        }
        var missing = fields.stream().filter(field -> !result.containsKey(field)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                "missing " + name + " fields: " + missing);
        }
        return result;
    }

    private static String text(Object raw, String field) {
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value;
    }

    private static void validateTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (var path : paths.toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalArgumentException(
                        "plugin package must not contain symbolic links");
                }
                var attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory() && !attributes.isRegularFile()) {
                    throw new IllegalArgumentException(
                        "plugin package must contain only directories and regular files");
                }
            }
        }
    }

    private static String digest(Path source) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(DIGEST_PREFIX);
            if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                digest.update((byte) 'F');
                updateFile(digest, source);
            } else if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                digest.update((byte) 'T');
                try (var paths = Files.walk(source)) {
                    var entries = paths.filter(path -> !path.equals(source))
                        .sorted(Comparator.comparing(path -> normalizedRelative(source, path),
                            PluginPackage::compareUtf8))
                        .toList();
                    for (var path : entries) {
                        var relative = normalizedRelative(source, path)
                            .getBytes(StandardCharsets.UTF_8);
                        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                            digest.update((byte) 'D');
                        } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                            digest.update((byte) 'F');
                        } else {
                            throw new IOException(
                                "content tree contains an unsupported entry: " + path);
                        }
                        updateLength(digest, relative.length);
                        digest.update(relative);
                        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                            updateFile(digest, path);
                        }
                    }
                }
            } else {
                throw new IOException("content must be a regular file or directory");
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        } catch (IOException failure) {
            throw digestFailure(source, "cannot compute content identity", failure);
        }
    }

    private static void updateFile(MessageDigest digest, Path path) throws IOException {
        var expected = Files.size(path);
        updateLength(digest, expected);
        long actual = 0;
        try (var input = Files.newInputStream(path)) {
            var buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
                actual += read;
            }
        }
        if (actual != expected) {
            throw new IOException("content changed while computing its digest: " + path);
        }
    }

    private static void updateLength(MessageDigest digest, long length) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(length).array());
    }

    private static String normalizedRelative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static int compareUtf8(String left, String right) {
        return java.util.Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8),
            right.getBytes(StandardCharsets.UTF_8));
    }

    private static ArtifactException digestFailure(Path path, String message,
                                                   Throwable cause) {
        return new ArtifactException(ArtifactPhase.DIGEST, message, path, cause);
    }

    private record Snapshot(PluginPackage pluginPackage, byte[] manifestBytes) {
        private boolean sameContent(Snapshot other) {
            return Arrays.equals(manifestBytes, other.manifestBytes)
                && pluginPackage.pluginId.equals(other.pluginPackage.pluginId)
                && pluginPackage.version.equals(other.pluginPackage.version)
                && pluginPackage.packageDigest.equals(other.pluginPackage.packageDigest)
                && pluginPackage.facets.equals(other.pluginPackage.facets);
        }
    }
}
