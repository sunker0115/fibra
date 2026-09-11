package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.config.DesiredInputGraph;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** 完整部署目标；制品精确选择与未绑定的配置声明共享一个内容身份。 */
public record DeploymentManifest(Map<ArtifactId, String> artifacts, DesiredInputGraph desiredGraph) {
    public DeploymentManifest {
        artifacts = Map.copyOf(artifacts);
        artifacts.values().forEach(DeploymentManifest::validateRevision);
        Objects.requireNonNull(desiredGraph, "desiredGraph");
    }

    public String revision() {
        return digest(DeploymentManifestCodec.encode(this));
    }

    static String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static void validateRevision(String revision) {
        if (revision == null || !revision.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("revision must be a lowercase SHA-256 digest");
        }
    }
}
