package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.config.ConfigLimits;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** 只解析 profile 明确选择的本地 package 路径；package 身份由 store 严格读取。 */
final class ProfilePackageSource {
    private static final ConfigLimits LIMITS = ConfigLimits.defaults();
    private static final YAMLMapper YAML = YAMLMapper.builder(YAMLFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(LIMITS.maxDepth()).maxStringLength(LIMITS.maxStringLength())
                .maxDocumentLength(LIMITS.maxFileBytes()).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final Path manifest;
    private final Path packagesRoot;

    ProfilePackageSource(Path manifest, Path packagesRoot) {
        this.manifest = Objects.requireNonNull(manifest, "manifest").toAbsolutePath().normalize();
        this.packagesRoot = Objects.requireNonNull(packagesRoot, "packagesRoot").toAbsolutePath().normalize();
    }

    List<Path> load() {
        try {
            if (Files.size(manifest) > LIMITS.maxFileBytes()) throw invalid("manifest exceeds the config file size limit", null);
            var value = YAML.readValue(Files.readAllBytes(manifest), Object.class);
            if (!(value instanceof List<?> entries)) throw invalid("manifest must be a string array; use [] for an empty selection", null);
            if (entries.size() > LIMITS.maxEntriesPerFile()) throw invalid("manifest exceeds the config entry limit", null);
            var selected = new LinkedHashSet<Path>();
            for (var entry : entries) {
                if (!(entry instanceof String name) || name.isBlank()) throw invalid("every package path must be a non-blank string", null);
                var relative = Path.of(name);
                var path = packagesRoot.resolve(relative).normalize();
                if (relative.isAbsolute() || !path.startsWith(packagesRoot) || path.equals(packagesRoot)) throw invalid("package path must stay inside the candidate root: " + name, null);
                if (!selected.add(path)) throw invalid("duplicate package path: " + name, null);
            }
            if (selected.isEmpty()) return List.of();
            var root = packagesRoot.toRealPath();
            var real = new HashSet<Path>();
            for (var path : selected) {
                var resolved = path.toRealPath();
                if (!resolved.startsWith(root) || resolved.equals(root)) throw invalid("package path resolves outside the candidate root: " + path, null);
                if (!real.add(resolved)) throw invalid("duplicate package path: " + path, null);
            }
            return List.copyOf(selected);
        } catch (IOException | tools.jackson.core.JacksonException failure) {
            throw invalid("cannot read package selection", failure);
        }
    }

    private IllegalArgumentException invalid(String message, Throwable cause) {
        return new IllegalArgumentException(manifest + ": " + message, cause);
    }
}
