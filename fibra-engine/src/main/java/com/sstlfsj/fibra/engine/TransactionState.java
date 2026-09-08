package com.sstlfsj.fibra.engine;

public enum TransactionState {
    PREPARED,
    COMMITTING,
    COMMITTED,
    PUBLISHED,
    RETIRED,
    ROLLED_BACK,
    RECOVERY_FAILED
}
