package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ExecutionTarget;

import java.util.Set;

/** execution runtime 纯编译产生的不可变目标计划。 */
public interface ExecutionTargetPlan {
    ExecutionTarget executionTarget();
    DeploymentTarget target();
    Set<ArtifactId> referencedArtifacts();
}
