package com.sstlfsj.fibra.event;

import com.sstlfsj.fibra.Context;

@FunctionalInterface
public interface EventTarget {
    boolean accepts(Context listenerContext);
}
