package com.sstlfsj.fibra.event;

/** 事件契约固定的派发方式。 */
public enum EventMode {
    EMIT,
    PARALLEL,
    SERIAL,
    BAIL,
    WATERFALL
}
