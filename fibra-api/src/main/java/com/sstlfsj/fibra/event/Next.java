package com.sstlfsj.fibra.event;

@FunctionalInterface
public interface Next<R> {
    R call();
}
