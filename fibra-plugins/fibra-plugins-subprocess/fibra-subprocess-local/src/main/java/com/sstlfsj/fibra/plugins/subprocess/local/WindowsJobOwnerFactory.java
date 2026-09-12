package com.sstlfsj.fibra.plugins.subprocess.local;

import com.sstlfsj.fibra.plugins.subprocess.SubprocessSpec;

import java.io.IOException;

/** Probes and creates the native Windows Job owner for one subprocess. */
@FunctionalInterface
interface WindowsJobOwnerFactory {
    default void probe() throws IOException {
    }

    WindowsJobOwner create(SubprocessSpec spec) throws IOException;
}
