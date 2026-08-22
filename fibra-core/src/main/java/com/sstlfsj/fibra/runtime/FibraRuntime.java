package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.internal.DefaultContext;

/** Fibra 内核的唯一创建入口。 */
public final class FibraRuntime {
    private FibraRuntime() {
    }

    public static Context create() {
        return new DefaultContext();
    }
}
