package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EngineCrashRecoveryTest {
    @ParameterizedTest
    @EnumSource(value = EngineTransactionState.class, names = {
        "COMMITTING_ARTIFACTS", "COMMITTING_CONFIG", "VERIFYING", "ROLLING_BACK"
    })
    void restoresTheOldGraphFromEveryIncompleteCommitState(
        EngineTransactionState state, @TempDir Path work) throws Exception {
        var installed = Files.createDirectory(work.resolve("plugins"));
        var config = work.resolve("host/fibra.yaml");
        Files.createDirectories(config.getParent());
        var transaction = Files.createDirectories(installed.resolve(
            ".fibra-engine/transactions/tx-states"));
        var artifactPrevious = Files.createDirectories(
            transaction.resolve("artifacts/previous/provider"));
        var artifactInstalled = Files.createDirectories(installed.resolve("provider"));
        Files.writeString(artifactPrevious.resolve("plugin.properties"), "old-artifact");
        Files.writeString(artifactInstalled.resolve("plugin.properties"), "new-artifact");
        var configPrevious = transaction.resolve("config/previous/fibra.yaml");
        Files.createDirectories(configPrevious.getParent());
        Files.writeString(configPrevious, "old-config");
        Files.writeString(config, state == EngineTransactionState.COMMITTING_ARTIFACTS
            ? "old-config" : "new-config");

        var journal = journal("tx-states", artifactPrevious, artifactInstalled,
            configPrevious, "new-config", state);
        journal.write(transaction);

        new EngineCrashRecovery(installed, config).recover();

        assertEquals("old-artifact",
            Files.readString(installed.resolve("provider/plugin.properties")));
        assertEquals("old-config", Files.readString(config));
        assertFalse(Files.exists(transaction));
    }

    @Test
    void removesNewArtifactAndConfigWhenTheOldGraphDidNotContainThem(
        @TempDir Path work) throws Exception {
        var installed = Files.createDirectory(work.resolve("plugins"));
        var config = work.resolve("host/fibra.yaml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "new-config");
        var artifact = Files.createDirectories(installed.resolve("provider"));
        Files.writeString(artifact.resolve("plugin.properties"), "new-artifact");
        var transaction = Files.createDirectories(installed.resolve(
            ".fibra-engine/transactions/tx-new"));
        var journal = EngineTransactionJournal.preparing("tx-new")
            .prepared("deployment", "1.0.0", "d".repeat(64),
                List.of(new EngineTransactionJournal.Artifact("provider", false, null,
                    EngineTransactionJournal.digest(artifact))),
                List.of(new EngineTransactionJournal.ConfigFile("fibra.yaml", false, null,
                    EngineTransactionJournal.digest(config))))
            .advance(EngineTransactionState.COMMITTING_ARTIFACTS)
            .advance(EngineTransactionState.COMMITTING_CONFIG)
            .advance(EngineTransactionState.VERIFYING);
        journal.write(transaction);

        new EngineCrashRecovery(installed, config).recover();

        assertFalse(Files.exists(installed.resolve("provider")));
        assertFalse(Files.exists(config));
        assertFalse(Files.exists(transaction));
    }

    @Test
    void cleansPreparingAndPreparedTransactionsWithoutChangingTheOldGraph(
        @TempDir Path work) throws Exception {
        var installed = Files.createDirectory(work.resolve("plugins"));
        var config = Files.writeString(work.resolve("fibra.yaml"), "old-config");
        var artifact = Files.createDirectories(installed.resolve("provider"));
        Files.writeString(artifact.resolve("plugin.properties"), "old-artifact");
        var preparing = Files.createDirectories(installed.resolve(
            ".fibra-engine/transactions/tx-preparing"));
        EngineTransactionJournal.preparing("tx-preparing").write(preparing);
        Files.createDirectories(preparing.resolve("input"));
        var prepared = Files.createDirectories(installed.resolve(
            ".fibra-engine/transactions/tx-prepared"));
        EngineTransactionJournal.preparing("tx-prepared")
            .prepared("deployment", "1.0.0", "e".repeat(64),
                List.of(new EngineTransactionJournal.Artifact("provider", true,
                    EngineTransactionJournal.digest(artifact), "f".repeat(64))),
                List.of(new EngineTransactionJournal.ConfigFile("fibra.yaml", true,
                    EngineTransactionJournal.digest(config), "a".repeat(64))))
            .write(prepared);

        new EngineCrashRecovery(installed, config).recover();

        assertEquals("old-artifact", Files.readString(
            installed.resolve("provider/plugin.properties")));
        assertEquals("old-config", Files.readString(config));
        assertFalse(Files.exists(preparing));
        assertFalse(Files.exists(prepared));
    }

    @Test
    void restoresArtifactAndConfigFromAPartialCommit(@TempDir Path work)
        throws Exception {
        var installed = Files.createDirectory(work.resolve("plugins"));
        var config = work.resolve("host/fibra.yaml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "new-config");
        var transaction = Files.createDirectories(installed.resolve(
            ".fibra-engine/transactions/tx-1"));
        var artifactPrevious = Files.createDirectories(
            transaction.resolve("artifacts/previous/provider"));
        var artifactInstalled = Files.createDirectories(installed.resolve("provider"));
        Files.writeString(artifactPrevious.resolve("plugin.properties"), "old-artifact");
        Files.writeString(artifactInstalled.resolve("plugin.properties"), "new-artifact");
        var configPrevious = transaction.resolve("config/previous/fibra.yaml");
        Files.createDirectories(configPrevious.getParent());
        Files.writeString(configPrevious, "old-config");
        var artifacts = List.of(new EngineTransactionJournal.Artifact("provider", true,
            EngineTransactionJournal.digest(artifactPrevious),
            EngineTransactionJournal.digest(artifactInstalled)));
        var configs = List.of(new EngineTransactionJournal.ConfigFile("fibra.yaml", true,
            EngineTransactionJournal.digest(configPrevious),
            EngineTransactionJournal.digest(config)));
        var journal = EngineTransactionJournal.preparing("tx-1")
            .prepared("deployment", "1.0.0", "a".repeat(64), artifacts, configs)
            .advance(EngineTransactionState.COMMITTING_ARTIFACTS)
            .advance(EngineTransactionState.COMMITTING_CONFIG);
        journal.write(transaction);

        new EngineCrashRecovery(installed, config).recover();

        assertEquals("old-artifact",
            Files.readString(installed.resolve("provider/plugin.properties")));
        assertEquals("old-config", Files.readString(config));
        assertFalse(Files.exists(transaction));
    }

    @Test
    void cleansCommittedPayloadOnlyAfterValidatingTheNewState(@TempDir Path work)
        throws Exception {
        var installed = Files.createDirectory(work.resolve("plugins"));
        var config = work.resolve("host/fibra.yaml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "new-config");
        var artifact = Files.createDirectories(installed.resolve("provider"));
        Files.writeString(artifact.resolve("plugin.properties"), "new-artifact");
        var transaction = Files.createDirectories(installed.resolve(
            ".fibra-engine/transactions/tx-2"));
        var journal = EngineTransactionJournal.preparing("tx-2")
            .prepared("deployment", "1.0.0", "b".repeat(64),
                List.of(new EngineTransactionJournal.Artifact("provider", false, null,
                    EngineTransactionJournal.digest(artifact))),
                List.of(new EngineTransactionJournal.ConfigFile("fibra.yaml", false, null,
                    EngineTransactionJournal.digest(config))))
            .advance(EngineTransactionState.COMMITTING_ARTIFACTS)
            .advance(EngineTransactionState.COMMITTING_CONFIG)
            .advance(EngineTransactionState.VERIFYING)
            .committed("c".repeat(64));
        journal.write(transaction);
        AppliedRevisionStore.write(installed, journal);

        new EngineCrashRecovery(installed, config).recover();

        assertFalse(Files.exists(transaction));
        assertEquals("new-artifact", Files.readString(artifact.resolve("plugin.properties")));
        assertEquals("new-config", Files.readString(config));
    }

    @Test
    void refusesPayloadWithoutJournalWhenPreviousStateExists(@TempDir Path work)
        throws Exception {
        var installed = Files.createDirectory(work.resolve("plugins"));
        var config = Files.writeString(work.resolve("fibra.yaml"), "[]");
        Files.createDirectories(installed.resolve(
            ".fibra-engine/transactions/orphan/artifacts/previous/provider"));

        var failure = assertThrows(FibraDeploymentException.class,
            () -> new EngineCrashRecovery(installed, config).recover());
        assertEquals(FibraDeploymentErrorStage.ROLLBACK, failure.stage());
    }

    @Test
    void refusesAnAmbiguousNewArtifactLocation(@TempDir Path work) throws Exception {
        var installed = Files.createDirectory(work.resolve("plugins"));
        var config = Files.writeString(work.resolve("fibra.yaml"), "old-config");
        var transaction = Files.createDirectories(installed.resolve(
            ".fibra-engine/transactions/tx-ambiguous"));
        var previous = Files.createDirectories(
            transaction.resolve("artifacts/previous/provider"));
        var current = Files.createDirectories(installed.resolve("provider"));
        var next = Files.createDirectories(
            transaction.resolve("artifacts/next/provider"));
        Files.writeString(previous.resolve("plugin.properties"), "old-artifact");
        Files.writeString(current.resolve("plugin.properties"), "new-artifact");
        Files.writeString(next.resolve("plugin.properties"), "new-artifact");
        var journal = EngineTransactionJournal.preparing("tx-ambiguous")
            .prepared("deployment", "1.0.0", "b".repeat(64),
                List.of(new EngineTransactionJournal.Artifact("provider", true,
                    EngineTransactionJournal.digest(previous),
                    EngineTransactionJournal.digest(current))), List.of())
            .advance(EngineTransactionState.COMMITTING_ARTIFACTS);
        journal.write(transaction);

        var failure = assertThrows(FibraDeploymentException.class,
            () -> new EngineCrashRecovery(installed, config).recover());

        assertEquals(FibraDeploymentErrorStage.ROLLBACK, failure.stage());
        assertEquals("new-artifact", Files.readString(
            installed.resolve("provider/plugin.properties")));
        assertEquals("old-artifact", Files.readString(
            transaction.resolve("artifacts/previous/provider/plugin.properties")));
    }

    private static EngineTransactionJournal journal(String transactionId,
                                                     Path artifactPrevious,
                                                     Path artifactInstalled,
                                                     Path configPrevious,
                                                     String newConfig,
                                                     EngineTransactionState state)
        throws Exception {
        var oldConfigDigest = EngineTransactionJournal.digest(configPrevious);
        var newConfigPath = Files.createTempFile("fibra-new-config", ".yaml");
        try {
            Files.writeString(newConfigPath, newConfig);
            var prepared = EngineTransactionJournal.preparing(transactionId)
                .prepared("deployment", "1.0.0", "a".repeat(64),
                    List.of(new EngineTransactionJournal.Artifact("provider", true,
                        EngineTransactionJournal.digest(artifactPrevious),
                        EngineTransactionJournal.digest(artifactInstalled))),
                    List.of(new EngineTransactionJournal.ConfigFile("fibra.yaml", true,
                        oldConfigDigest, EngineTransactionJournal.digest(newConfigPath))))
                .advance(EngineTransactionState.COMMITTING_ARTIFACTS);
            if (state == EngineTransactionState.COMMITTING_ARTIFACTS) {
                return prepared;
            }
            var committingConfig = prepared.advance(EngineTransactionState.COMMITTING_CONFIG);
            if (state == EngineTransactionState.COMMITTING_CONFIG) {
                return committingConfig;
            }
            var verifying = committingConfig.advance(EngineTransactionState.VERIFYING);
            return state == EngineTransactionState.ROLLING_BACK
                ? verifying.rollingBack() : verifying;
        } finally {
            Files.deleteIfExists(newConfigPath);
        }
    }
}
