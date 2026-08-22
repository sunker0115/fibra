package com.sstlfsj.fibra.loader.pf4j;

import java.util.concurrent.CopyOnWriteArrayList;

public final class PluginLifecycleRecorder {
    public static final CopyOnWriteArrayList<String> EVENTS = new CopyOnWriteArrayList<>();

    private PluginLifecycleRecorder() {
    }
}
