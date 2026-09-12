package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.config.ConfigLimits;
import com.sstlfsj.fibra.engine.DeploymentArtifact;
import com.sstlfsj.fibra.engine.InitialArtifactSource;
import com.sstlfsj.fibra.engine.PluginArtifactProbe;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** 仅首次初始化或显式完整 apply 采集；不保存活动选择，不扫描候选目录。 */
final class ProfileArtifactSource implements InitialArtifactSource {
    private static final ConfigLimits LIMITS = ConfigLimits.defaults();
    private static final YAMLMapper YAML = YAMLMapper.builder(YAMLFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(LIMITS.maxDepth()).maxStringLength(LIMITS.maxStringLength())
                .maxDocumentLength(LIMITS.maxFileBytes()).build())
            .build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    private final Path manifest;
    private final Path pluginsRoot;
    private final PluginArtifactProbe probe;

    ProfileArtifactSource(Path manifest, Path pluginsRoot, PluginArtifactProbe probe) {
        this.manifest = Objects.requireNonNull(manifest, "manifest").toAbsolutePath().normalize();
        this.pluginsRoot = Objects.requireNonNull(pluginsRoot, "pluginsRoot").toAbsolutePath().normalize();
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    @Override
    public List<DeploymentArtifact> load() {
        var paths = selectedPaths();
        var result = new ArrayList<DeploymentArtifact>();
        var ids = new HashSet<ArtifactId>();
        for (var path : paths) {
            var artifact = Objects.requireNonNull(probe.probe(path).block(), "artifact probe result");
            if (!ids.add(artifact.artifactId())) {
                throw invalid("duplicate artifact id: " + artifact.artifactId().value(), null);
            }
            result.add(artifact);
        }
        return List.copyOf(result);
    }

    private List<Path> selectedPaths() {
        try {
            if (Files.size(manifest) > LIMITS.maxFileBytes()) {
                throw invalid("manifest exceeds the config file size limit", null);
            }
            var value = YAML.readValue(Files.readAllBytes(manifest), Object.class);
            if (!(value instanceof List<?> entries)) {
                throw invalid("manifest must be a string array; use [] for an empty selection", null);
            }
            if (entries.size() > LIMITS.maxEntriesPerFile()) {
                throw invalid("manifest exceeds the config entry limit", null);
            }
            var paths = new LinkedHashSet<Path>();
            for (var entry : entries) {
                if (!(entry instanceof String name) || name.isBlank()) {
                    throw invalid("every artifact path must be a non-blank string", null);
                }
                if (name.chars().anyMatch(character -> ":*?[]{}".indexOf(character) >= 0)) {
                    throw invalid("artifact paths must be local paths, not URLs or patterns: " + name, null);
                }
                var relative = Path.of(name);
                var path = pluginsRoot.resolve(relative).normalize();
                if (relative.isAbsolute() || !path.startsWith(pluginsRoot) || path.equals(pluginsRoot)) {
                    throw invalid("artifact path must stay inside the candidate root: " + name, null);
                }
                if (!paths.add(path)) throw invalid("duplicate artifact path: " + name, null);
            }
            if (paths.isEmpty()) return List.of();
            var realRoot = pluginsRoot.toRealPath();
            var realPaths = new HashSet<Path>();
            for (var path : paths) {
                var real = path.toRealPath();
                if (!real.startsWith(realRoot) || real.equals(realRoot)) {
                    throw invalid("artifact path resolves outside the candidate root: " + path, null);
                }
                if (!realPaths.add(real)) throw invalid("duplicate artifact path: " + path, null);
            }
            return List.copyOf(paths);
        } catch (IOException exception) {
            throw invalid("cannot read artifact selection or selected package", exception);
        } catch (tools.jackson.core.JacksonException exception) {
            throw invalid("cannot parse artifact selection", exception);
        }
    }

    private IllegalArgumentException invalid(String message, Throwable cause) {
        return new IllegalArgumentException(manifest + ": " + message, cause);
    }
}
