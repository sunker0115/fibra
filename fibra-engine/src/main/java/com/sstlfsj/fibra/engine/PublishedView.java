package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.bridge.ContributionSnapshot;

public record PublishedView(String viewRevision,
    EngineSnapshot engine,
    ContributionSnapshot contributions,
    RuntimeDiagnostics diagnostics,
    EngineDiagnostics engineDiagnostics) {
    public PublishedView {
        if (viewRevision == null || viewRevision.isBlank()) throw new IllegalArgumentException("viewRevision must not be blank");
        java.util.Objects.requireNonNull(engine, "engine");
        java.util.Objects.requireNonNull(contributions, "contributions");
        java.util.Objects.requireNonNull(diagnostics, "diagnostics");
        java.util.Objects.requireNonNull(engineDiagnostics, "engineDiagnostics");
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String viewRevision;
        private EngineSnapshot engine;
        private ContributionSnapshot contributions;
        private RuntimeDiagnostics diagnostics;
        private EngineDiagnostics engineDiagnostics;
        public Builder viewRevision(String value) { viewRevision = value; return this; }
        public Builder engine(EngineSnapshot value) { engine = value; return this; }
        public Builder contributions(ContributionSnapshot value) { contributions = value; return this; }
        public Builder diagnostics(RuntimeDiagnostics value) { diagnostics = value; return this; }
        public Builder engineDiagnostics(EngineDiagnostics value) { engineDiagnostics = value; return this; }
        public PublishedView build() { return new PublishedView(viewRevision, engine, contributions, diagnostics, engineDiagnostics); }
    }
}
