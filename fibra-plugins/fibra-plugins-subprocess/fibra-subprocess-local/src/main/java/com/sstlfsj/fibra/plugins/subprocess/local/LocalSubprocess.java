package com.sstlfsj.fibra.plugins.subprocess.local;

import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.plugins.subprocess.ProcessUnit;
import com.sstlfsj.fibra.plugins.subprocess.Subprocess;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessErrorCode;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessException;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessSpec;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Objects;

public final class LocalSubprocess implements Subprocess {
    private static final long MAX_TIMER_DELAY_MILLIS = Integer.MAX_VALUE;
    private final String nodeExecutable;

    public LocalSubprocess(String nodeExecutable) {
        if (nodeExecutable == null || nodeExecutable.isBlank()) {
            throw new IllegalArgumentException("nodeExecutable must not be blank");
        }
        this.nodeExecutable = nodeExecutable;
    }

    @Override
    public Mono<ProcessUnit> spawn(InvocationContext invocation, SubprocessSpec spec) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(spec, "spec");
        return Mono.<ProcessUnit>fromCallable(() -> {
            final long graceMillis;
            try {
                graceMillis = spec.grace().toMillis();
            } catch (ArithmeticException overflow) {
                throw unsupportedGrace(overflow);
            }
            if (graceMillis > MAX_TIMER_DELAY_MILLIS) throw unsupportedGrace(null);
            var unit = new LocalProcessUnit();
            unit.ownedBy(invocation.effects().add(unit));
            unit.cancelOn(invocation.cancellation());
            unit.launch(nodeExecutable, spec);
            return unit;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static SubprocessException unsupportedGrace(Throwable cause) {
        return new SubprocessException(SubprocessErrorCode.SPAWN_FAILED,
            "grace must not exceed " + MAX_TIMER_DELAY_MILLIS + " ms", cause);
    }
}
