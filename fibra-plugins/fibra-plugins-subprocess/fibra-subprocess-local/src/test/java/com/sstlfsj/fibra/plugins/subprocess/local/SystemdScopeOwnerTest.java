package com.sstlfsj.fibra.plugins.subprocess.local;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SystemdScopeOwnerTest {
    @Test void pendingMissingIsNotQuietWhileTheDirectLauncherLives() throws Exception {
        var commands = new FakeCommands(missing(), state("loaded", "inactive", null));
        var owner = owner(commands);

        assertEquals(SystemdScopeOwner.Observation.PENDING, owner.observe(true).observation());
        assertEquals(SystemdScopeOwner.Observation.QUIET, owner.observe(true).observation());
    }

    @Test void establishedScopeTreatsRapidCollectMissingAsQuiet() throws Exception {
        var owner = owner(new FakeCommands(missing()));
        owner.established();

        assertEquals(SystemdScopeOwner.Observation.QUIET, owner.observe(true).observation());
    }

    @Test void strictStateParserAcceptsNotSetAndRejectsMalformedShapes() throws Exception {
        var parsed = SystemdScopeOwner.parseState(
            "LoadState=loaded\nActiveState=active\nTasksCurrent=[not set]\n");
        assertEquals("loaded", parsed.loadState());
        assertEquals("active", parsed.activeState());
        assertEquals(null, parsed.tasksCurrent());

        for (var value : List.of(
            "loaded\nActiveState=active\n",
            "LoadState=loaded\nLoadState=loaded\nActiveState=active\n",
            "LoadState=loaded\n",
            "LoadState=loaded\nActiveState=active\nOther=x\n",
            "LoadState=loaded\nActiveState=active\nTasksCurrent=many\n")) {
            assertThrows(IOException.class, () -> SystemdScopeOwner.parseState(value));
        }
    }

    @Test void activeDescendantsReceiveTermGraceKillBeforeQuiet() throws Exception {
        var commands = new FakeCommands(state("loaded", "active", 2),
            state("loaded", "active", 1), state("loaded", "inactive", 0));
        var owner = owner(commands);

        owner.awaitQuietAfterExit(Duration.ZERO);

        assertEquals(List.of("show", "kill:SIGTERM", "show", "kill:SIGKILL", "show"),
            commands.events);
    }

    @Test void activeEmptyScopeStopsWhileManagerAndMalformedFailuresRemainLoud() throws Exception {
        var empty = new FakeCommands(state("loaded", "active", 0),
            state("not-found", "inactive", null));
        owner(empty).awaitQuietAfterExit(Duration.ofMillis(1));
        assertEquals(List.of("show", "stop", "show"), empty.events);

        var denied = new FakeCommands(new SystemdCommandResult(1, "", "permission denied"));
        assertThrows(IOException.class, () -> owner(denied).observe(false));
        var malformed = new FakeCommands(new SystemdCommandResult(0,
            "LoadState=loaded\nActiveState=mystery\n", ""));
        assertThrows(IOException.class, () -> owner(malformed).observe(false));
    }

    @Test void stopMustProveTheEmptyScopeBecameQuiet() {
        var commands = new FakeCommands(state("loaded", "active", 0),
            state("loaded", "active", 1));

        assertThrows(IOException.class,
            () -> owner(commands, Duration.ZERO).awaitQuietAfterExit(Duration.ZERO));
        assertEquals(List.of("show", "stop", "show"), commands.events);
    }

    @Test void collectRacesMakeEstablishedKillAndStopIdempotent() throws Exception {
        var killed = new FakeCommands(state("loaded", "active", 1), missing())
            .mutations(missing());
        owner(killed).awaitQuietAfterExit(Duration.ZERO);
        assertEquals(List.of("show", "kill:SIGTERM", "show"), killed.events);

        var stopped = new FakeCommands(state("loaded", "active", 0), missing())
            .mutations(missing());
        owner(stopped).awaitQuietAfterExit(Duration.ZERO);
        assertEquals(List.of("show", "stop", "show"), stopped.events);
    }

    @Test void observationUsesDeadlineBoundedExponentialBackoff() {
        var now = new AtomicLong();
        var sleeps = new ArrayList<Long>();
        SystemdCommands active = argv -> argv.contains("show")
            ? state("loaded", "active", 1) : new SystemdCommandResult(0, "", "");
        var owner = new SystemdScopeOwner("fibra-test.scope", active, delay -> {
            sleeps.add(delay);
            now.addAndGet(Duration.ofMillis(delay).toNanos());
        }, Duration.ofSeconds(1), now::get);

        assertThrows(IOException.class,
            () -> owner.awaitQuietAfterExit(Duration.ofMillis(350)));

        assertEquals(List.of(50L, 100L, 200L, 50L, 100L, 200L, 400L, 250L), sleeps);
    }

    @Test void stateValuesAreExactAndTasksCurrentSupportsLongCounts() throws Exception {
        var parsed = SystemdScopeOwner.parseState(
            "LoadState=loaded\nActiveState=active\nTasksCurrent=2147483648\n");
        assertEquals(2147483648L, parsed.tasksCurrent());
        assertThrows(IOException.class, () -> SystemdScopeOwner.parseState(
            "LoadState=LOADED\nActiveState=active\nTasksCurrent=0\n"));
        assertThrows(IOException.class, () -> SystemdScopeOwner.parseState(
            "LoadState=loaded\nActiveState=ACTIVE\nTasksCurrent=0\n"));
    }

    private static SystemdScopeOwner owner(FakeCommands commands) {
        return new SystemdScopeOwner("fibra-test.scope", commands, delay -> { });
    }

    private static SystemdScopeOwner owner(FakeCommands commands, Duration quiescence) {
        return new SystemdScopeOwner("fibra-test.scope", commands, delay -> { }, quiescence);
    }

    private static SystemdCommandResult missing() {
        return new SystemdCommandResult(1, "", "Unit fibra-test.scope could not be found");
    }

    private static SystemdCommandResult state(String load, String active, Integer tasks) {
        return new SystemdCommandResult(0, "LoadState=" + load + "\nActiveState=" + active + "\n"
            + (tasks == null ? "" : "TasksCurrent=" + tasks + "\n"), "");
    }

    private static final class FakeCommands implements SystemdCommands {
        private final ArrayDeque<SystemdCommandResult> results;
        private final ArrayDeque<SystemdCommandResult> mutations = new ArrayDeque<>();
        private final ArrayList<String> events = new ArrayList<>();

        private FakeCommands(SystemdCommandResult... results) {
            this.results = new ArrayDeque<>(List.of(results));
        }

        private FakeCommands mutations(SystemdCommandResult... results) {
            mutations.addAll(List.of(results));
            return this;
        }

        @Override public SystemdCommandResult execute(List<String> argv) {
            if (argv.contains("show")) {
                events.add("show");
                return results.removeFirst();
            }
            if (argv.contains("stop")) events.add("stop");
            else events.add("kill:" + argv.stream().filter(value -> value.startsWith("--signal="))
                .findFirst().orElseThrow().substring("--signal=".length()));
            return mutations.isEmpty()
                ? new SystemdCommandResult(0, "", "") : mutations.removeFirst();
        }
    }
}
