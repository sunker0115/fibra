package com.sstlfsj.fibra;

@FunctionalInterface
public interface EffectSink {
    void add(Disposable disposable);
}
