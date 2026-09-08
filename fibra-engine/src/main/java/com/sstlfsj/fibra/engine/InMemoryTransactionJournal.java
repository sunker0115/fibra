package com.sstlfsj.fibra.engine;

import java.util.ArrayList;
import java.util.List;

public final class InMemoryTransactionJournal implements TransactionJournal {
    private final List<TransactionRecord> records = new ArrayList<>();

    @Override
    public synchronized void append(TransactionRecord record) {
        records.add(record);
    }

    @Override
    public synchronized List<TransactionRecord> records() {
        return List.copyOf(records);
    }
}
