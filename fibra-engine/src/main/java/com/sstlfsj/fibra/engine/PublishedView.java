package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.bridge.ContributionSnapshot;

import java.util.Objects;

/** 一个 view revision 下不可拆分的 Engine、贡献和诊断事实。 */
public record PublishedView(String viewRevision, String generationRevision,
                            EngineSnapshot engine,
                            ContributionSnapshot contributions,
                            RuntimeDiagnostics diagnostics,
                            EngineDiagnostics engineDiagnostics) {
    public PublishedView {
        if (viewRevision == null || viewRevision.isBlank()) {
            throw new IllegalArgumentException("viewRevision must not be blank");
        }
        if (generationRevision == null || generationRevision.isBlank()) {
            throw new IllegalArgumentException("generationRevision must not be blank");
        }
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(contributions, "contributions");
        Objects.requireNonNull(diagnostics, "diagnostics");
        Objects.requireNonNull(engineDiagnostics, "engineDiagnostics");
    }
}
