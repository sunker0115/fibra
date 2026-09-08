package com.sstlfsj.fibra.engine;

import java.time.Instant;
import java.util.List;

public record TransactionRecord(String transactionId, TransactionState state,
                                List<String> participants, String detail,
                                Instant recordedAt) {
    public TransactionRecord {
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("transactionId must not be blank");
        }
        participants = List.copyOf(participants);
        recordedAt = recordedAt == null ? Instant.now() : recordedAt;
    }
}
