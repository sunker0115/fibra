package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.RuntimeId;

final class UnknownRuntimeException extends IllegalArgumentException {
    UnknownRuntimeException(RuntimeId runtimeId) {
        super("no runtime adapter is registered for " + runtimeId.value());
    }
}
