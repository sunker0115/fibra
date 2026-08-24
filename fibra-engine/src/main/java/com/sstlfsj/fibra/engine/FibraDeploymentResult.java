package com.sstlfsj.fibra.engine;

import java.util.List;
import java.util.Objects;

public record FibraDeploymentResult(String deploymentId,
                                    String deploymentVersion,
                                    String appliedRevision,
                                    List<String> changedArtifactIds) {
    public FibraDeploymentResult {
        deploymentId = requireText(deploymentId, "deploymentId");
        deploymentVersion = requireText(deploymentVersion, "deploymentVersion");
        appliedRevision = requireText(appliedRevision, "appliedRevision");
        changedArtifactIds = List.copyOf(changedArtifactIds);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
