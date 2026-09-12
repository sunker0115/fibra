package com.sstlfsj.fibra.plugins.subprocess.local;

import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.plugins.subprocess.ProcessUnit;
import com.sstlfsj.fibra.plugins.subprocess.Subprocess;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessErrorCode;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessException;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessSpec;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LocalSubprocess implements Subprocess {
    private static final long MAX_TIMER_DELAY_MILLIS = Integer.MAX_VALUE;
    private final String nodeExecutable;
    private final boolean windows;
    private final WindowsJobOwnerFactory windowsOwners;
    private final AtomicBoolean warnedUnavailableWindowsJob = new AtomicBoolean();

    public LocalSubprocess(String nodeExecutable) {
        this(nodeExecutable, System.getProperty("os.name", "").startsWith("Windows"),
            JnaWindowsJobOwner.FACTORY);
    }

    LocalSubprocess(String nodeExecutable, boolean windows,
                    WindowsJobOwnerFactory windowsOwners) {
        if (nodeExecutable == null || nodeExecutable.isBlank()) {
            throw new IllegalArgumentException("nodeExecutable must not be blank");
        }
        this.nodeExecutable = nodeExecutable;
        this.windows = windows;
        this.windowsOwners = Objects.requireNonNull(windowsOwners, "windowsOwners");
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
            if (windows && windowsJobAvailable(invocation)) {
                var unit = new WindowsJobProcessUnit(windowsOwners);
                unit.ownedBy(invocation.effects().add(unit));
                unit.cancelOn(invocation.cancellation());
                unit.launch(spec);
                return unit;
            } else {
                var unit = new LocalProcessUnit();
                unit.ownedBy(invocation.effects().add(unit));
                unit.cancelOn(invocation.cancellation());
                unit.launch(nodeExecutable, spec);
                return unit;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private boolean windowsJobAvailable(InvocationContext invocation) {
        try {
            windowsOwners.probe();
            return true;
        } catch (IOException | LinkageError unavailable) {
            if (warnedUnavailableWindowsJob.compareAndSet(false, true)) {
                invocation.logger().warn(
                    "Windows Job Object unavailable; using weaker supervisor fallback where descendants "
                        + "may escape and managed-range exit cannot be guaranteed", unavailable);
            }
            return false;
        }
    }

    private static SubprocessException unsupportedGrace(Throwable cause) {
        return new SubprocessException(SubprocessErrorCode.SPAWN_FAILED,
            "grace must not exceed " + MAX_TIMER_DELAY_MILLIS + " ms", cause);
    }
}
