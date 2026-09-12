package com.sstlfsj.fibra.artifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** 经严格校验的运行时中立安装单元；payload 的内部格式由对应 runtime 解释。 */
public final class ArtifactPackage {
    private static final Set<String> FIELDS = Set.of("formatVersion", "runtime", "payload");

    private final Path root;
    private final RuntimeId runtimeId;
    private final Path payload;

    private ArtifactPackage(Path root, RuntimeId runtimeId, Path payload) {
        this.root = root;
        this.runtimeId = runtimeId;
        this.payload = payload;
    }

    public Path root() { return root; }
    public RuntimeId runtimeId() { return runtimeId; }
    public Path payload() { return payload; }

    /** 读取目录根的 plugin.properties，不接受裸制品或符号链接。 */
    public static ArtifactPackage read(Path source) {
        Objects.requireNonNull(source, "source");
        var normalized = source.toAbsolutePath().normalize();
        try {
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("artifact package must be a directory, not a symbolic link");
            }
            var root = normalized.toRealPath();
            try (var paths = Files.walk(root)) {
                if (paths.anyMatch(Files::isSymbolicLink)) {
                    throw new IllegalArgumentException("artifact package must not contain symbolic links");
                }
            }
            var manifest = root.resolve("plugin.properties");
            if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("artifact package requires a regular plugin.properties");
            }
            var values = new Properties() {
                @Override
                public synchronized Object put(Object key, Object value) {
                    if (!FIELDS.contains(key)) {
                        throw new IllegalArgumentException("unknown artifact package field: " + key);
                    }
                    if (containsKey(key)) {
                        throw new IllegalArgumentException("duplicate artifact package field: " + key);
                    }
                    return super.put(key, value);
                }
            };
            try (var reader = Files.newBufferedReader(manifest)) {
                values.load(reader);
            }
            if (!values.keySet().equals(FIELDS)) {
                throw new IllegalArgumentException("artifact package requires formatVersion, runtime and payload");
            }
            if (!"1".equals(values.getProperty("formatVersion"))) {
                throw new IllegalArgumentException("unsupported artifact package formatVersion");
            }
            var runtimeId = new RuntimeId(values.getProperty("runtime"));
            var value = values.getProperty("payload");
            if (value.isBlank()) {
                throw new IllegalArgumentException("artifact package payload must not be blank");
            }
            var relative = Path.of(value);
            var payload = root.resolve(relative).normalize();
            if (relative.isAbsolute() || !payload.startsWith(root) || payload.equals(root)) {
                throw new IllegalArgumentException("artifact package payload must be inside its root");
            }
            if (!Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(payload, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("artifact package payload must be an existing file or directory");
            }
            return new ArtifactPackage(root, runtimeId, payload.toRealPath());
        } catch (IOException | IllegalArgumentException failure) {
            throw new ArtifactException(ArtifactPhase.VALIDATE,
                "invalid artifact package: " + failure.getMessage(), normalized, failure);
        }
    }
}
