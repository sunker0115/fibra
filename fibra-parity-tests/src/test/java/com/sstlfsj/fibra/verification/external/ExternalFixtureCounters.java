package com.sstlfsj.fibra.verification.external;

/** 验证用不可变计数快照。 */
public record ExternalFixtureCounters(int preparations, int seals, int reconciliations,
                                      int activations, int drains, int stops,
                                      int aborts, int retires,
                                      int resourceGenerations, int resourceLeases) {
    public ExternalFixtureCounters {
        if (preparations < 0 || seals < 0 || reconciliations < 0 || activations < 0
            || drains < 0 || stops < 0 || aborts < 0 || retires < 0
            || resourceGenerations < 0 || resourceLeases < 0) {
            throw new IllegalArgumentException("fixture counters must not be negative");
        }
    }
}
