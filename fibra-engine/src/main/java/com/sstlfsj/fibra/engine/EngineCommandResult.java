package com.sstlfsj.fibra.engine;

import java.util.List;

public record EngineCommandResult(PublishedView view, List<String> warnings) {
    public EngineCommandResult {
        warnings = List.copyOf(warnings);
    }
}
