package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Fibra;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.AfterEach;

import java.time.Duration;

abstract class CordisSpecSupport {
    protected final Context root = FibraRuntime.create();

    @AfterEach
    final void closeRoot() {
        root.closeAsync().block(Duration.ofSeconds(5));
    }

    protected static Fibra await(Fibra fibra) {
        return fibra.await().block(Duration.ofSeconds(5));
    }
}
