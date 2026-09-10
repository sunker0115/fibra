package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.PluginInstance;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.AfterEach;

import java.time.Duration;

abstract class CordisSpecSupport {
    protected static final Duration TIMEOUT = Duration.ofSeconds(5);
    protected final FibraRuntime runtime = FibraRuntime.create();
    protected final Context root = runtime.rootScope().context();

    @AfterEach
    final void closeRoot() {
        runtime.closeAsync().block(TIMEOUT);
    }

    protected static <C> PluginInstance<C> await(PluginInstance<C> instance) {
        return instance.settled().block(TIMEOUT);
    }
}
