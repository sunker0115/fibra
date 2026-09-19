package com.sstlfsj.fibra.verification.external;

import java.util.List;
import java.util.Objects;

/** 测试控制器唯一暴露的只读观察面。 */
public record ExternalFixtureRuntimeSnapshot(boolean online,
                                             ExternalFixtureCounters counters,
                                             List<ExternalFixtureEvent> events) {
    public ExternalFixtureRuntimeSnapshot {
        counters = Objects.requireNonNull(counters, "counters");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
    }
}
