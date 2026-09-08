package com.sstlfsj.fibra.engine;

public final class MutationGateClosedException extends IllegalStateException {
    MutationGateClosedException() {
        super("engine mutation gate is permanently closed");
    }
}
