package com.sstlfsj.fibra.engine;

enum EngineTransactionState {
    PREPARING,
    PREPARED,
    COMMITTING_ARTIFACTS,
    COMMITTING_CONFIG,
    VERIFYING,
    COMMITTED,
    ROLLING_BACK
}
