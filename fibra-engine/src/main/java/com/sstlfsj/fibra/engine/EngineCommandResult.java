package com.sstlfsj.fibra.engine;

import java.util.List;

public record EngineCommandResult(EngineSnapshot snapshot, List<String> warnings) {
    public EngineCommandResult {
        warnings = List.copyOf(warnings);
    }
}
