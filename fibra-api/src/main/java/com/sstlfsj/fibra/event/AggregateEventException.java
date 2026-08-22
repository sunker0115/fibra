package com.sstlfsj.fibra.event;

import java.util.List;

public final class AggregateEventException extends RuntimeException {
    private final List<Throwable> causes;

    public AggregateEventException(List<? extends Throwable> causes) {
        super(causes.size() + " event listeners failed");
        this.causes = List.copyOf(causes);
        this.causes.forEach(this::addSuppressed);
    }

    public List<Throwable> causes() {
        return causes;
    }
}
