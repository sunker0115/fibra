package com.sstlfsj.fibra.plugins.subprocess.local;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Strict observer and final owner of one transient user scope. */
final class SystemdScopeOwner implements LinuxScopeRange {
    private static final Pattern MISSING_UNIT = Pattern.compile(
        "\\bunit\\b[^\\r\\n]*(?:could not be found|not found|not loaded)",
        Pattern.CASE_INSENSITIVE);
    private static final Duration QUIESCENCE = Duration.ofSeconds(1);
    private final String unit;
    private final SystemdCommands commands;
    private final Sleeper sleeper;
    private final Duration quiescence;
    private final LongSupplier nanoTime;
    private boolean established;

    SystemdScopeOwner(String unit, SystemdCommands commands, Sleeper sleeper) {
        this(unit, commands, sleeper, QUIESCENCE);
    }

    SystemdScopeOwner(String unit, SystemdCommands commands, Sleeper sleeper,
                      Duration quiescence) {
        this(unit, commands, sleeper, quiescence, System::nanoTime);
    }

    SystemdScopeOwner(String unit, SystemdCommands commands, Sleeper sleeper,
                      Duration quiescence, LongSupplier nanoTime) {
        this.unit = unit;
        this.commands = commands;
        this.sleeper = sleeper;
        this.quiescence = quiescence;
        this.nanoTime = nanoTime;
    }

    @Override
    public synchronized void established() {
        established = true;
    }

    ScopeObservation observe(boolean directAlive) throws IOException {
        var result = commands.execute(List.of("systemctl", "--user", "show", unit,
            "--property=LoadState", "--property=ActiveState", "--property=TasksCurrent"));
        String combined = result.stdout() + "\n" + result.stderr();
        if (result.status() != 0) {
            if (MISSING_UNIT.matcher(combined).find()) {
                return new ScopeObservation(established || !directAlive
                    ? Observation.QUIET : Observation.PENDING, null);
            }
            throw new IOException("systemctl could not read " + unit + ": " + diagnostic(result));
        }
        var state = parseState(result.stdout());
        if (state.loadState().equals("not-found") && state.activeState().equals("inactive")) {
            return new ScopeObservation(established || !directAlive
                ? Observation.QUIET : Observation.PENDING, state.tasksCurrent());
        }
        if (!state.loadState().equals("loaded")) {
            throw new IOException("systemctl returned unknown LoadState: " + state.loadState());
        }
        established();
        if (state.activeState().equals("inactive") || state.activeState().equals("failed")) {
            return new ScopeObservation(Observation.QUIET, state.tasksCurrent());
        }
        if (!List.of("active", "activating", "reloading", "deactivating")
            .contains(state.activeState())) {
            throw new IOException("systemctl returned unknown ActiveState: " + state.activeState());
        }
        return new ScopeObservation(Observation.ACTIVE, state.tasksCurrent());
    }

    @Override
    public void awaitQuietAfterExit(Duration grace) throws Exception {
        settle(grace);
    }

    @Override
    public void forceQuiet(Duration grace) throws Exception {
        settle(grace);
    }

    private void settle(Duration grace) throws Exception {
        var observed = observe(false);
        if (observed.observation() == Observation.QUIET) return;
        if (observed.tasksCurrent() != null && observed.tasksCurrent() == 0) {
            requireMutationSuccess(
                commands.execute(List.of("systemctl", "--user", "stop", unit)), "stop");
            if (!awaitQuiet(quiescence)) {
                throw new IOException("systemd scope did not become quiet after stop: " + unit);
            }
            return;
        }
        signal("SIGTERM");
        if (awaitQuiet(grace)) return;
        signal("SIGKILL");
        if (!awaitQuiet(QUIESCENCE)) {
            throw new IOException("systemd scope did not become quiet after SIGKILL: " + unit);
        }
    }

    private boolean awaitQuiet(Duration budget) throws Exception {
        long deadline = nanoTime.getAsLong() + budget.toNanos();
        long delayMillis = 50;
        do {
            if (observe(false).observation() == Observation.QUIET) return true;
            long remainingNanos = deadline - nanoTime.getAsLong();
            if (remainingNanos <= 0) return false;
            long remainingMillis = remainingNanos / 1_000_000L;
            if (remainingMillis == 0) return false;
            sleeper.sleep(Math.min(delayMillis, remainingMillis));
            delayMillis = Math.min(5000, delayMillis * 2);
        } while (true);
    }

    private void signal(String signal) throws IOException {
        requireMutationSuccess(commands.execute(List.of("systemctl", "--user", "kill",
            "--kill-whom=all", "--signal=" + signal, unit)), "signal " + signal);
    }

    private static void requireMutationSuccess(SystemdCommandResult result, String operation)
        throws IOException {
        if (result.status() != 0 && !MISSING_UNIT.matcher(
            result.stdout() + "\n" + result.stderr()).find()) {
            throw new IOException("systemctl could not " + operation + ": " + diagnostic(result));
        }
    }

    static UnitState parseState(String stdout) throws IOException {
        var values = new HashMap<String, String>();
        for (var line : stdout.split("\\R")) {
            if (line.isEmpty()) continue;
            int separator = line.indexOf('=');
            if (separator <= 0) throw new IOException("systemctl returned malformed state");
            String name = line.substring(0, separator);
            if (values.putIfAbsent(name, line.substring(separator + 1)) != null) {
                throw new IOException("systemctl returned duplicate " + name);
            }
        }
        if (!values.keySet().stream().allMatch(
            name -> List.of("LoadState", "ActiveState", "TasksCurrent").contains(name))
            || values.size() < 2 || !values.containsKey("LoadState")
            || !values.containsKey("ActiveState")) {
            throw new IOException("systemctl returned incomplete state");
        }
        Long tasks = null;
        String reported = values.get("TasksCurrent");
        if (reported != null && !reported.equals("[not set]")) {
            if (!reported.chars().allMatch(Character::isDigit) || reported.isEmpty()) {
                throw new IOException("systemctl returned non-numeric TasksCurrent");
            }
            try {
                tasks = Long.valueOf(reported);
            } catch (NumberFormatException overflow) {
                throw new IOException("systemctl returned invalid TasksCurrent", overflow);
            }
        }
        String loadState = values.get("LoadState");
        String activeState = values.get("ActiveState");
        if (!List.of("loaded", "not-found").contains(loadState)) {
            throw new IOException("systemctl returned unknown LoadState: " + loadState);
        }
        if (!List.of("inactive", "failed", "active", "activating", "reloading",
            "deactivating").contains(activeState)) {
            throw new IOException("systemctl returned unknown ActiveState: " + activeState);
        }
        return new UnitState(loadState, activeState, tasks);
    }

    private static String diagnostic(SystemdCommandResult result) {
        String value = (result.stdout() + "\n" + result.stderr()).trim();
        return value.isEmpty() ? "exit " + result.status() : value;
    }

    enum Observation { PENDING, ACTIVE, QUIET }

    record ScopeObservation(Observation observation, Long tasksCurrent) { }

    record UnitState(String loadState, String activeState, Long tasksCurrent) { }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}

record SystemdCommandResult(int status, String stdout, String stderr) { }

@FunctionalInterface
interface SystemdCommands {
    SystemdCommandResult execute(List<String> argv) throws IOException;
}
