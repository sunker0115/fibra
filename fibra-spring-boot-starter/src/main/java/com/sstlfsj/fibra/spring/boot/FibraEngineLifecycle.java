package com.sstlfsj.fibra.spring.boot;

import com.sstlfsj.fibra.engine.EngineState;
import com.sstlfsj.fibra.engine.FibraEngine;
import org.springframework.context.SmartLifecycle;

import java.util.Objects;

final class FibraEngineLifecycle implements SmartLifecycle {
    private final FibraEngine engine;

    FibraEngineLifecycle(FibraEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public void start() {
        engine.start().block();
    }

    @Override
    public void stop() {
        engine.close();
    }

    @Override
    public boolean isRunning() {
        return engine.published().current().engine().state() == EngineState.RUNNING;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
