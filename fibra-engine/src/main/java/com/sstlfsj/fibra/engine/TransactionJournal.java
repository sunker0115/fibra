package com.sstlfsj.fibra.engine;

import java.util.List;

public interface TransactionJournal extends AutoCloseable {
    void append(TransactionRecord record);

    List<TransactionRecord> records();

    @Override
    default void close() {
    }
}
