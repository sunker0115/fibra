package com.sstlfsj.fibra.engine;

/** 以同一 durable token 替换失败 unit 及其 dependent closure。 */
public record ReconcileCurrent() implements EngineCommand { }
