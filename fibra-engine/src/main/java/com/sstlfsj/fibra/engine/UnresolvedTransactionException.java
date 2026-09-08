package com.sstlfsj.fibra.engine;

import java.util.List;

public final class UnresolvedTransactionException extends RuntimeException {
    private final List<TransactionRecord> transactions;

    public UnresolvedTransactionException(List<TransactionRecord> transactions) {
        super("transaction journal contains unresolved changes: "
            + transactions.stream().map(TransactionRecord::transactionId).toList());
        this.transactions = List.copyOf(transactions);
    }

    public List<TransactionRecord> transactions() {
        return transactions;
    }
}
