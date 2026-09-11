package com.sstlfsj.fibra.engine;

public sealed interface EngineCommand permits RefreshDesired, ReplaceDesiredGraph,
    ReplaceConfigContext, InstallArtifact, UninstallArtifact, ApplyDeployment {
    String expectedRevision();
}
