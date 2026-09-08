package com.sstlfsj.fibra.engine;

import java.util.List;

public record ChangeSetResult(String transactionId, List<String> warnings) {
    public ChangeSetResult {
        warnings = List.copyOf(warnings);
    }
}
