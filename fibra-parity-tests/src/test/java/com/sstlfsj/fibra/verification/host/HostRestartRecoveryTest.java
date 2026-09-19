package com.sstlfsj.fibra.verification.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostRestartRecoveryTest {
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(10);

    @TempDir Path work;

    @Test
    void independentHostsRecoverOnlyTheDurableTargetAndFenceOldRuntimeFacts()
        throws Exception {
        var seedReport = work.resolve("reports/host-a.properties");
        run("seed", seedReport);
        var hostA = read(seedReport);
        var target = work.resolve("target/target.json");
        var durableBytes = Files.readAllBytes(target);

        var oldSupervisor = number(hostA, "a.node.supervisorPid");
        var oldPayload = number(hostA, "a.node.payloadPid");
        awaitDead(oldSupervisor);
        awaitDead(oldPayload);

        assertRecoveryRefusalPreservesTarget("missing-provider",
            durableBytes, target);
        assertRecoveryRefusalPreservesTarget("old-built-in-digest",
            durableBytes, target);
        assertRecoveryRefusalPreservesTarget("metadata-definition-mismatch",
            durableBytes, target);

        var packageLocation = Path.of(
            hostA.getProperty("javaPackageLocation"));
        var heldPackage = work.resolve("held-java-package");
        Files.move(packageLocation, heldPackage,
            StandardCopyOption.ATOMIC_MOVE);
        try {
            assertRecoveryRefusalPreservesTarget("missing-package",
                durableBytes, target);
        } finally {
            Files.move(heldPackage, packageLocation,
                StandardCopyOption.ATOMIC_MOVE);
        }

        var pluginJar = packageLocation.resolve("plugin.jar");
        var pluginBytes = Files.readAllBytes(pluginJar);
        Files.writeString(pluginJar, "corrupt", StandardCharsets.UTF_8);
        try {
            assertRecoveryRefusalPreservesTarget("corrupt-package",
                durableBytes, target);
        } finally {
            Files.write(pluginJar, pluginBytes);
        }

        Files.createDirectories(work.resolve("recovery-ready"));
        var recoveryReport = work.resolve("reports/host-b.properties");
        run("recover", recoveryReport, seedReport);
        var hostB = read(recoveryReport);

        assertEquals(hostA.getProperty("targetRevision"),
            hostB.getProperty("targetRevision"));
        assertEquals(hostA.getProperty("targetDigest"),
            hostB.getProperty("targetDigest"));
        assertArrayEquals(durableBytes, Files.readAllBytes(target));
        assertNotEquals(hostA.getProperty("a.hostInstanceId"),
            hostB.getProperty("b.hostInstanceId"));
        assertNotEquals(hostA.getProperty("a.hostProcessPid"),
            hostB.getProperty("b.hostProcessPid"));
        assertNotEquals(hostA.getProperty("a.viewRevision"),
            hostB.getProperty("b.viewRevision"));
        assertEquals("true", hostB.getProperty("oldTupleRejected"));
        assertEquals("true", hostB.getProperty("businessIdentityStable"));
        assertEquals("true",
            hostB.getProperty("registrationFenceRejected"));
        assertEquals(hostA.getProperty("old.provider"),
            hostB.getProperty("current.provider"));

        for (var entry : List.of("java-entry", "node-entry", "external-a",
            "external-b", "built-in-entry")) {
            assertNotEquals(hostA.getProperty("a." + entry
                    + ".runtimeInstanceId"),
                hostB.getProperty("b." + entry + ".runtimeInstanceId"),
                entry + " reused Host A runtime identity");
            assertNotEquals(hostA.getProperty("a." + entry
                    + ".lifecycleOperationId"),
                hostB.getProperty("b." + entry + ".lifecycleOperationId"),
                entry + " reused Host A lifecycle identity");
        }

        assertNotEquals(hostA.getProperty("a.javaMarker"),
            hostB.getProperty("b.javaMarker"),
            "Host B must create a new dynamic Java ClassLoader in its JVM");
        assertNotEquals(oldSupervisor,
            number(hostB, "b.node.supervisorPid"));
        assertNotEquals(oldPayload, number(hostB, "b.node.payloadPid"));
        awaitDead(number(hostB, "b.node.supervisorPid"));
        awaitDead(number(hostB, "b.node.payloadPid"));
    }

    private void assertRecoveryRefusalPreservesTarget(String scenario,
                                                      byte[] targetBytes,
                                                      Path target)
        throws Exception {
        var report = work.resolve("reports/refusal-" + scenario
            + ".properties");
        run("verify-recovery-refusal", report, scenario);
        var values = read(report);
        assertEquals(scenario, values.getProperty("scenario"));
        assertEquals("metadata-definition-mismatch".equals(scenario)
                ? "rejected-before-host" : "blocked-recoverable",
            values.getProperty("outcome"));
        assertEquals("true", values.getProperty("targetUnchanged"));
        assertFalse(values.getProperty("failure").isBlank());
        assertArrayEquals(targetBytes, Files.readAllBytes(target));
    }

    private void run(String mode, Path report, Path seedReport)
        throws Exception {
        run(mode, report, seedReport.toString());
    }

    private void run(String mode, Path report, String... extra)
        throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java");
        var classpath = System.getProperty("surefire.test.class.path",
            System.getProperty("java.class.path"));
        var command = new ArrayList<String>();
        command.add(java.toString());
        command.add("-Dorg.slf4j.simpleLogger.defaultLogLevel=warn");
        var node = System.getProperty("fibra.test.node");
        if (node != null && !node.isBlank()) {
            command.add("-Dfibra.test.node=" + node);
        }
        command.add("-cp");
        command.add(classpath);
        command.add(HostRestartRecoveryProcess.class.getName());
        command.add(mode);
        command.add(work.toString());
        command.add(report.toString());
        command.addAll(Arrays.asList(extra));
        var output = work.resolve("reports/" + mode + '-'
            + report.getFileName() + ".log");
        Files.createDirectories(output.getParent());
        var process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
        if (!process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            var descendants = process.descendants().toList();
            descendants.reversed().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(EXIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            throw new AssertionError("Host process timed out: " + command
                + System.lineSeparator() + Files.readString(output));
        }
        assertEquals(0, process.exitValue(), () -> "Host process failed: "
            + command + System.lineSeparator() + readQuietly(output));
        assertTrue(Files.isRegularFile(report),
            () -> "Host process did not write report: " + report);
    }

    private void run(String mode, Path report) throws Exception {
        run(mode, report, new String[0]);
    }

    private static Properties read(Path path) throws IOException {
        var values = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            values.load(input);
        }
        return values;
    }

    private static long number(Properties values, String name) {
        return Long.parseLong(values.getProperty(name));
    }

    private static void awaitDead(long pid) throws Exception {
        var deadline = System.nanoTime() + EXIT_TIMEOUT.toNanos();
        while (alive(pid) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(alive(pid), "process remains alive: " + pid);
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static String readQuietly(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            return "cannot read process output: " + failure;
        }
    }
}
