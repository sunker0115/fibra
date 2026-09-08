package com.sstlfsj.fibra.engine;

public record RefreshDesired(String expectedRevision) implements EngineCommand {
}
