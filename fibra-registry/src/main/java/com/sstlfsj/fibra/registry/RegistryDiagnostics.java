package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.engine.observation.EngineDiagnostics;
import com.sstlfsj.fibra.engine.observation.EngineSnapshot;

import java.util.Objects;

/** 同一次 PublishedView 的高级诊断；字段与语义属于正式支持合同。 */
public record RegistryDiagnostics(String viewRevision, EngineSnapshot engine,
                                  EngineDiagnostics engineDiagnostics) {
    public RegistryDiagnostics {
        Objects.requireNonNull(viewRevision, "viewRevision");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(engineDiagnostics, "engineDiagnostics");
    }
}
