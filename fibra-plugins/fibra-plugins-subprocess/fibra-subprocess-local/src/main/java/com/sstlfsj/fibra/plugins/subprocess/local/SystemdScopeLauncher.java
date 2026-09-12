package com.sstlfsj.fibra.plugins.subprocess.local;

import com.sstlfsj.fibra.plugins.subprocess.SubprocessSpec;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Literal-argv systemd-run launcher and capability probes. */
final class SystemdScopeLauncher implements LinuxScopeProvider {
    private static final int DIAGNOSTIC_LIMIT = 64 * 1024;
    private static final long COMMAND_TIMEOUT_SECONDS = 5;
    private static final long COMMAND_REAP_MILLIS = 100;
    private static final long QUIESCENCE_MILLIS = 1000;
    static final SystemdScopeLauncher INSTANCE = new SystemdScopeLauncher(
        "systemd-run", "systemctl", new ProcessCommands(),
        (argv, directory) -> new ProcessBuilder(argv).directory(directory).start(),
        () -> "fibra-subprocess-" + ProcessHandle.current().pid() + "-"
            + UUID.randomUUID().toString().replace("-", ""), System.getenv());

    private final String systemdRun;
    private final String systemctl;
    private final SystemdCommands commands;
    private final ProcessStarter starter;
    private final UnitNames unitNames;
    private final Map<String, String> environment;

    SystemdScopeLauncher(String systemdRun, String systemctl, SystemdCommands commands,
                         ProcessStarter starter, UnitNames unitNames,
                         Map<String, String> environment) {
        this.systemdRun = systemdRun;
        this.systemctl = systemctl;
        this.commands = commands;
        this.starter = starter;
        this.unitNames = unitNames;
        this.environment = Map.copyOf(environment);
    }

    @Override
    public void deepProbe() throws IOException {
        validateInvocationId();
        String unit = unitNames.next();
        requireSuccess(commands.execute(List.of(systemdRun, "--user", "--scope", "--quiet",
            "--collect", "--expand-environment=no", "--unit=" + unit, "--", systemctl,
            "--user", "show", unit + ".scope", "--property=ActiveState", "--value")),
            "systemd scope probe");
    }

    @Override
    public void probeManager() throws IOException {
        requireSuccess(commands.execute(List.of(systemctl, "--user", "show", "--property=Version",
            "--value")), "systemd user manager probe");
    }

    @Override
    public LinuxScopeLaunch launch(String nodeExecutable, SubprocessSpec spec) throws IOException {
        String unit = unitNames.next();
        var argv = new ArrayList<>(List.of(systemdRun, "--user", "--scope", "--quiet", "--collect",
            "--expand-environment=no", "--unit=" + unit, "--", nodeExecutable,
            "--input-type=module", "-e", supervisorScript(), "--", "linux-scope",
            Long.toString(spec.grace().toMillis()), Long.toString(QUIESCENCE_MILLIS),
            Integer.toString(spec.stdoutMaxBytes()), Integer.toString(spec.stderrMaxBytes())));
        argv.addAll(spec.argv());
        var process = starter.start(argv, new File(spec.cwd()));
        var range = new SystemdScopeOwner(unit + ".scope", remapSystemctl(commands, systemctl),
            Thread::sleep);
        try {
            writeInvocationFrame(process, environment);
            return new LinuxScopeLaunch(process, range);
        } catch (IOException failure) {
            try {
                process.destroyForcibly();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw new LinuxScopeLaunchException(
                "Unable to initialize the Linux scope supervisor", failure,
                new LinuxScopeLaunch(process, range));
        }
    }

    static String supervisorScript() throws IOException {
        try (var source = SystemdScopeLauncher.class.getResourceAsStream("process-supervisor.mjs")) {
            if (source == null) throw new IOException("missing bundled process supervisor");
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void writeInvocationFrame(Process process, Map<String, String> environment)
        throws IOException {
        boolean present = environment.containsKey("INVOCATION_ID");
        String encoded = present ? Base64.getEncoder().encodeToString(
            environment.get("INVOCATION_ID").getBytes(StandardCharsets.UTF_8)) : "";
        var frame = "I\t" + (present ? "1" : "0") + "\t" + encoded + "\n";
        process.getOutputStream().write(frame.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
    }

    private void validateInvocationId() throws IOException {
        String value = environment.get("INVOCATION_ID");
        if (value != null && !value.matches("[0-9A-Fa-f]{32}")) {
            throw new IOException("INVOCATION_ID cannot be restored by the Linux scope runner");
        }
    }

    private static SystemdCommands remapSystemctl(SystemdCommands delegate, String systemctl) {
        return argv -> {
            var mapped = new ArrayList<>(argv);
            mapped.set(0, systemctl);
            return delegate.execute(mapped);
        };
    }

    private static void requireSuccess(SystemdCommandResult result, String operation)
        throws IOException {
        if (result.status() != 0) {
            String diagnostic = (result.stdout() + "\n" + result.stderr()).trim();
            throw new IOException(operation + " failed: "
                + (diagnostic.isEmpty() ? "exit " + result.status() : diagnostic));
        }
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(List<String> argv, File directory) throws IOException;
    }

    @FunctionalInterface
    interface UnitNames {
        String next();
    }

    static final class ProcessCommands implements SystemdCommands {
        private final Duration timeout;
        private final CommandStarter starter;

        ProcessCommands() {
            this(Duration.ofSeconds(COMMAND_TIMEOUT_SECONDS), argv -> {
                var builder = new ProcessBuilder(argv);
                builder.environment().put("LC_ALL", "C");
                builder.environment().remove("SYSTEMD_LOG_TARGET");
                return builder.start();
            });
        }

        ProcessCommands(Duration timeout, CommandStarter starter) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            this.starter = Objects.requireNonNull(starter, "starter");
        }

        @Override public SystemdCommandResult execute(List<String> argv) throws IOException {
            var process = starter.start(argv);
            long deadline = System.nanoTime() + timeout.toNanos();
            var stdout = collect(process.getInputStream());
            var stderr = collect(process.getErrorStream());
            try {
                if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) {
                    throw new TimeoutException("systemd command process did not exit");
                }
                return new SystemdCommandResult(process.exitValue(), await(stdout, deadline),
                    await(stderr, deadline));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cleanup(process, deadline);
                throw new IOException("Interrupted while waiting for systemd command", interrupted);
            } catch (TimeoutException timeout) {
                cleanup(process, deadline);
                throw new IOException("systemd command exceeded its 5 second deadline", timeout);
            } catch (ExecutionException collectionFailure) {
                cleanup(process, deadline);
                throw new IOException("Unable to collect systemd command output",
                    collectionFailure.getCause());
            }
        }

        private static String await(CompletableFuture<String> output, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
            return output.get(remaining(deadline), TimeUnit.NANOSECONDS);
        }

        private static long remaining(long deadline) throws TimeoutException {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("systemd command deadline expired");
            return remaining;
        }

        private static void cleanup(Process process, long deadline) {
            long reapDeadline = Math.max(deadline,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COMMAND_REAP_MILLIS));
            try { process.destroyForcibly(); } catch (RuntimeException ignored) { }
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getErrorStream().close(); } catch (IOException ignored) { }
            try {
                long remaining = reapDeadline - System.nanoTime();
                if (remaining > 0) process.waitFor(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private static CompletableFuture<String> collect(java.io.InputStream input) {
            return CompletableFuture.supplyAsync(() -> {
                try (input) {
                    var tail = new ByteArrayOutputStream();
                    var buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (count == 0) continue;
                        tail.write(buffer, 0, count);
                        if (tail.size() > DIAGNOSTIC_LIMIT) {
                            var bytes = tail.toByteArray();
                            tail.reset();
                            tail.write(bytes, bytes.length - DIAGNOSTIC_LIMIT, DIAGNOSTIC_LIMIT);
                        }
                    }
                    return tail.toString(StandardCharsets.UTF_8);
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            }, command -> Thread.ofVirtual().name("fibra-systemd-command-output").start(command));
        }

        @FunctionalInterface
        interface CommandStarter {
            Process start(List<String> argv) throws IOException;
        }
    }
}

@FunctionalInterface
interface LinuxScopeLaunchFactory {
    LinuxScopeLaunch launch(String nodeExecutable, SubprocessSpec spec) throws IOException;
}

interface LinuxScopeProvider extends LinuxScopeLaunchFactory {
    void deepProbe() throws IOException;

    void probeManager() throws IOException;
}

record LinuxScopeLaunch(Process process, LinuxScopeRange range) {
    LinuxScopeLaunch {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(range, "range");
    }
}

final class LinuxScopeLaunchException extends IOException {
    private final LinuxScopeLaunch launch;

    LinuxScopeLaunchException(String message, Throwable cause, LinuxScopeLaunch launch) {
        super(message, cause);
        this.launch = Objects.requireNonNull(launch, "launch");
    }

    LinuxScopeLaunch launch() {
        return launch;
    }
}

interface LinuxScopeRange {
    void established();

    void awaitQuietAfterExit(Duration grace) throws Exception;

    void forceQuiet(Duration grace) throws Exception;
}
