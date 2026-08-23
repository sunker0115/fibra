package com.sstlfsj.fibra.loader.config;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Fibra;

import java.util.Objects;

/** 已由配置 loader 挂载的一个 group、include 或插件运行实例。 */
public final class FibraConfigRuntimeEntry {
    private final FibraConfigEntry entry;
    private final Fibra fibra;
    private final Context context;

    FibraConfigRuntimeEntry(FibraConfigEntry entry, Fibra fibra, Context context) {
        this.entry = Objects.requireNonNull(entry, "entry");
        this.fibra = Objects.requireNonNull(fibra, "fibra");
        this.context = Objects.requireNonNull(context, "context");
    }

    public FibraConfigEntry entry() {
        return entry;
    }

    public Fibra fibra() {
        return fibra;
    }

    public Context context() {
        return context;
    }
}
