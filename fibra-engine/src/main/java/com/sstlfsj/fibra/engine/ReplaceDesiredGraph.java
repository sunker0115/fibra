package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.config.DesiredGraph;

import java.util.Objects;

public record ReplaceDesiredGraph(String expectedRevision, String expectedDesiredRevision,
                                  DesiredGraph graph) implements EngineCommand {
    public ReplaceDesiredGraph {
        if (expectedDesiredRevision == null || expectedDesiredRevision.isBlank()) {
            throw new IllegalArgumentException("expectedDesiredRevision must not be blank");
        }
        Objects.requireNonNull(graph, "graph");
    }
}
