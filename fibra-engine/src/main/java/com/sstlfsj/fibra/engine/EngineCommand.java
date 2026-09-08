package com.sstlfsj.fibra.engine;

public sealed interface EngineCommand permits RefreshDesired, ReplaceDesiredGraph,
    InstallArtifact, UninstallArtifact, ApplyDeployment {
    String expectedRevision();
}
