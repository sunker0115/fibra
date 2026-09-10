package com.sstlfsj.fibra.engine;

import java.util.List;

/** Engine 对运行代与事务的不可变管理面投影。 */
public record EngineDiagnostics(String currentGenerationRevision,
                                String candidateGenerationRevision,
                                List<String> drainingGenerationRevisions,
                                TransactionState transactionState,
                                boolean mutationGateOpen,
                                List<TransactionRecord> transactions,
                                String failure) {
    public EngineDiagnostics {
        drainingGenerationRevisions = List.copyOf(drainingGenerationRevisions);
        transactions = List.copyOf(transactions);
    }
}
