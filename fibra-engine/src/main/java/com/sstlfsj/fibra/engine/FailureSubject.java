package com.sstlfsj.fibra.engine;

import java.util.Objects;

/** 失败事实的精确所有者；每个变体只携带该 owner 的稳定身份。 */
public sealed interface FailureSubject {
    record Engine(String hostInstanceId) implements FailureSubject {
        public Engine {
            hostInstanceId = required(hostInstanceId, "hostInstanceId");
        }
    }

    record DurableTarget(long targetRevision, String targetDigest)
        implements FailureSubject {
        public DurableTarget {
            if (targetRevision < 1) {
                throw new IllegalArgumentException(
                    "targetRevision must be positive");
            }
            targetDigest = required(targetDigest, "targetDigest");
        }
    }

    record Operation(String operationId) implements FailureSubject {
        public Operation {
            operationId = required(operationId, "operationId");
        }
    }

    record Candidate(String attemptId) implements FailureSubject {
        public Candidate {
            attemptId = required(attemptId, "attemptId");
        }
    }

    record Current(String attemptId) implements FailureSubject {
        public Current {
            attemptId = required(attemptId, "attemptId");
        }
    }

    record Retirement(String batchId, String sourceAttemptId)
        implements FailureSubject {
        public Retirement {
            batchId = required(batchId, "batchId");
            sourceAttemptId = required(sourceAttemptId, "sourceAttemptId");
        }
    }

    record Unit(String ownerId, RuntimeUnitFence fence)
        implements FailureSubject {
        public Unit {
            ownerId = required(ownerId, "ownerId");
            Objects.requireNonNull(fence, "fence");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
