package com.sstlfsj.fibra.loader.pf4j;

enum PluginTransactionState {
    PREPARED,
    INSTALLING,
    APPLYING,
    COMMITTED;

    PluginTransactionState next() {
        return switch (this) {
            case PREPARED -> INSTALLING;
            case INSTALLING -> APPLYING;
            case APPLYING -> COMMITTED;
            case COMMITTED -> throw new IllegalStateException(
                "COMMITTED is the final plugin transaction state");
        };
    }
}
