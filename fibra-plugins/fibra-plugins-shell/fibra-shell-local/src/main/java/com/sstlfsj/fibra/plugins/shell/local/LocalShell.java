package com.sstlfsj.fibra.plugins.shell.local;

import com.sstlfsj.fibra.CancellationSource;
import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.plugins.shell.Shell;
import com.sstlfsj.fibra.plugins.shell.ShellErrorCode;
import com.sstlfsj.fibra.plugins.shell.ShellException;
import com.sstlfsj.fibra.plugins.shell.ShellOutput;
import com.sstlfsj.fibra.plugins.shell.ShellRequest;
import com.sstlfsj.fibra.plugins.shell.ShellResult;
import com.sstlfsj.fibra.plugins.subprocess.ProcessUnit;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessErrorCode;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessException;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessOutput;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessServices;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessSpec;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class LocalShell implements Shell {
    private static final long MAX_TIMER_DELAY_MILLIS = Integer.MAX_VALUE;
    private final ShellLocalConfig config;

    public LocalShell(ShellLocalConfig config) {
        this.config = config;
    }

    @Override
    public Mono<ShellResult> run(InvocationContext invocation, ShellRequest request) {
        return Mono.defer(() -> {
            long timeoutMillis = timeoutMillis(request.timeout());
            if (invocation.cancellation().isCancelled()) {
                var empty = ShellOutput.builder().text("").truncated(false).totalBytes(0).build();
                return Mono.just(ShellResult.builder().signal("SIGTERM").aborted(true)
                    .timeout(request.timeout()).stdout(empty).stderr(empty).build());
            }
            var reason = new AtomicReference<String>();
            var process = new AtomicReference<ProcessUnit>();
            var deadline = new CancellationSource();
            java.util.function.Consumer<String> stop = cause -> {
                if (reason.compareAndSet(null, cause)) deadline.cancel();
            };
            var cancellation = invocation.cancellation().cancelled()
                .subscribe(ignored -> { }, ignored -> stop.accept("aborted"), () -> stop.accept("aborted"));
            var timeout = Schedulers.parallel().schedule(() -> stop.accept("timeout"),
                timeoutMillis, TimeUnit.MILLISECONDS);
            var spec = SubprocessSpec.builder().argv(List.of(config.bashExecutable(), "-c", request.command()))
                .cwd(request.workdir()).stdoutMaxBytes(config.outputMaxBytes()).stderrMaxBytes(config.outputMaxBytes())
                .grace(Duration.ofMillis(config.graceMillis())).build();
            return Mono.defer(() -> invocation.withCancellation(deadline.token())
                .service(SubprocessServices.SUBPROCESS)
                .invoke((caller, subprocess) -> subprocess.spawn(caller, spec)))
                .doOnDiscard(ProcessUnit.class, ProcessUnit::terminate)
                .flatMap(unit -> {
                    process.set(unit);
                    return unit.done().flatMap(outcome -> {
                        reason.compareAndSet(null, "completed");
                        return unit.waitForExit().thenReturn(ShellResult.builder()
                            .exitCode(outcome.exitCode()).signal(outcome.signal())
                            .timedOut("timeout".equals(reason.get())).aborted("aborted".equals(reason.get()))
                            .timeout(request.timeout()).stdout(output(outcome.stdout()))
                            .stderr(output(outcome.stderr())).build());
                    }).onErrorResume(error -> {
                        unit.terminate();
                        return unit.waitForExit().then(Mono.error(error));
                    });
                })
                .onErrorResume(error -> recover(error, process.get(), reason.get(), request))
                .doOnCancel(() -> stop.accept("aborted"))
                .doFinally(ignored -> {
                    cancellation.dispose();
                    timeout.dispose();
                });
        });
    }

    private static Mono<ShellResult> recover(Throwable error, ProcessUnit process,
                                              String reason, ShellRequest request) {
        if (process == null && ("timeout".equals(reason) || "aborted".equals(reason))
                && !(error instanceof SubprocessException processError
                    && processError.code() == SubprocessErrorCode.TERMINATION_FAILED)) {
            return Mono.just(interrupted(request, reason));
        }
        if (error instanceof SubprocessException processError) {
            return Mono.error(new ShellException(
                processError.code() == SubprocessErrorCode.TERMINATION_FAILED
                    ? ShellErrorCode.TERMINATION_FAILED : ShellErrorCode.START_FAILED,
                processError.getMessage(), processError));
        }
        return Mono.error(error);
    }

    private static ShellResult interrupted(ShellRequest request, String reason) {
        var empty = ShellOutput.builder().text("").truncated(false).totalBytes(0).build();
        return ShellResult.builder().signal("SIGTERM")
            .timedOut("timeout".equals(reason)).aborted("aborted".equals(reason))
            .timeout(request.timeout()).stdout(empty).stderr(empty).build();
    }

    private static long timeoutMillis(Duration timeout) {
        final long millis;
        try {
            millis = timeout.toMillis();
        } catch (ArithmeticException overflow) {
            throw unsupportedTimeout(overflow);
        }
        if (millis > MAX_TIMER_DELAY_MILLIS) throw unsupportedTimeout(null);
        return millis;
    }

    private static ShellException unsupportedTimeout(Throwable cause) {
        return new ShellException(ShellErrorCode.START_FAILED,
            "timeout must not exceed " + MAX_TIMER_DELAY_MILLIS + " ms", cause);
    }

    private static ShellOutput output(SubprocessOutput output) {
        return ShellOutput.builder().text(output.text()).truncated(output.truncated())
            .totalBytes(output.totalBytes()).build();
    }
}
