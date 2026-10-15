package com.sstlfsj.fibra.engine.publication;

import com.sstlfsj.fibra.engine.observation.EngineDiagnostics;
import com.sstlfsj.fibra.engine.observation.EngineSnapshot;
import com.sstlfsj.fibra.engine.observation.RuntimeDiagnostics;

import com.sstlfsj.fibra.bridge.ContributionHandle;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionSnapshot;

import java.util.Optional;

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

    /**
     * 在本 view 内按 kind+id 原子捕获调用凭据：viewRevision 与当时的 registrationIdentity 来自同一快照。
     * 返回的 handle 只在本 view 代次有效，不可跨 view 缓存复用；找不到贡献时返回空。
     */
    public <D, I, O> Optional<ContributionHandle> resolve(ContributionKind<D, I, O> kind, ContributionId id) {
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(id, "id");
        return contributions.entries().stream()
            .filter(entry -> entry.kind().equals(kind.name()) && entry.id().equals(id))
            .findFirst()
            .map(entry -> new ContributionHandle(viewRevision, entry.registrationIdentity(), id));
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
