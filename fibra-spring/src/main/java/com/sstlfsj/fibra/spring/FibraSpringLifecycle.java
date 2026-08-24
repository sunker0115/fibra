package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.engine.FibraEngine;
import org.springframework.context.SmartLifecycle;

import java.util.Objects;

/** 只把 Spring 生命周期委托给唯一 FibraEngine。 */
public final class FibraSpringLifecycle implements SmartLifecycle {
    private final FibraEngine engine;

    public FibraSpringLifecycle(FibraEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public void start() {
        engine.start();
    }

    @Override
    public void stop() {
        engine.close();
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return engine.isRunning();
    }
}
