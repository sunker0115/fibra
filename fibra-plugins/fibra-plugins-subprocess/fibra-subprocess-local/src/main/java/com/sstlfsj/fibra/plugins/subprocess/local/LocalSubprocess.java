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
    private final HostPlatform platform;
    private final WindowsJobOwnerFactory windowsOwners;
    private final LinuxScopeProvider linuxScopes;
    private final AtomicBoolean warnedWeakerBoundary = new AtomicBoolean();
    private volatile boolean linuxDeepProbeSucceeded;

    public LocalSubprocess(String nodeExecutable) {
        this(nodeExecutable, currentPlatform(), JnaWindowsJobOwner.FACTORY,
            SystemdScopeLauncher.INSTANCE);
    }

    LocalSubprocess(String nodeExecutable, boolean windows,
                    WindowsJobOwnerFactory windowsOwners) {
        this(nodeExecutable, windows ? HostPlatform.WINDOWS : HostPlatform.OTHER,
            windowsOwners, SystemdScopeLauncher.INSTANCE);
    }

    LocalSubprocess(String nodeExecutable, HostPlatform platform,
                    WindowsJobOwnerFactory windowsOwners, LinuxScopeProvider linuxScopes) {
        if (nodeExecutable == null || nodeExecutable.isBlank()) {
            throw new IllegalArgumentException("nodeExecutable must not be blank");
        }
        this.nodeExecutable = nodeExecutable;
        this.platform = Objects.requireNonNull(platform, "platform");
        this.windowsOwners = Objects.requireNonNull(windowsOwners, "windowsOwners");
        this.linuxScopes = Objects.requireNonNull(linuxScopes, "linuxScopes");
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
            if (platform == HostPlatform.WINDOWS && windowsJobAvailable(invocation)) {
                var unit = new WindowsJobProcessUnit(windowsOwners);
                unit.ownedBy(invocation.effects().add(unit));
                unit.cancelOn(invocation.cancellation());
                unit.launch(spec);
                return unit;
            } else if (platform == HostPlatform.LINUX && linuxScopeAvailable(invocation)) {
                var unit = new LinuxScopeProcessUnit(linuxScopes, invocation.logger());
                unit.ownedBy(invocation.effects().add(unit));
                unit.cancelOn(invocation.cancellation());
                unit.launch(nodeExecutable, spec);
                return unit;
            } else {
                warnWeakerBoundary(invocation, null);
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
            warnWeakerBoundary(invocation, unavailable);
            return false;
        }
    }

    private boolean linuxScopeAvailable(InvocationContext invocation) {
        try {
            if (!linuxDeepProbeSucceeded) {
                synchronized (this) {
                    if (!linuxDeepProbeSucceeded) {
                        linuxScopes.deepProbe();
                        linuxDeepProbeSucceeded = true;
                    }
                }
            }
            linuxScopes.probeManager();
            return true;
        } catch (IOException | LinkageError unavailable) {
            warnWeakerBoundary(invocation, unavailable);
            return false;
        }
    }

    private void warnWeakerBoundary(InvocationContext invocation, Throwable cause) {
        if (!warnedWeakerBoundary.compareAndSet(false, true)) return;
        String message = platform == HostPlatform.WINDOWS
            ? "Windows Job Object unavailable; using weaker supervisor fallback where descendants may "
                + "escape and managed-range exit cannot be guaranteed"
            : "Persistent managed-process range unavailable; using native POSIX process-group boundary "
                + "where setsid or reparented descendants may escape and waitForExit cannot guarantee "
                + "managed-range quiescence";
        if (cause == null) invocation.logger().warn(message);
        else invocation.logger().warn(message, cause);
    }

    private static HostPlatform currentPlatform() {
        String name = System.getProperty("os.name", "");
        if (name.startsWith("Windows")) return HostPlatform.WINDOWS;
        if (name.equals("Linux")) return HostPlatform.LINUX;
        return HostPlatform.OTHER;
    }

    enum HostPlatform { WINDOWS, LINUX, OTHER }

    private static SubprocessException unsupportedGrace(Throwable cause) {
        return new SubprocessException(SubprocessErrorCode.SPAWN_FAILED,
            "grace must not exceed " + MAX_TIMER_DELAY_MILLIS + " ms", cause);
    }
}
