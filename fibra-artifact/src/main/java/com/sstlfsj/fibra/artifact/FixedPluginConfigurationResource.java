package com.sstlfsj.fibra.artifact;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.NoSuchElementException;

/** 同步资源 owner：原始 I/O 异常只在调用栈中存在，终态仅保存安全分类。 */
final class FixedPluginConfigurationResource implements PluginConfigurationResource {
    private static final int MAX_BYTES = 1024 * 1024;

    private final Path path;
    private final ConfigurationResourceEvidence evidence;
    private final ResourceIo io;
    private InputStream input;
    private boolean readAttempted;
    private boolean closeAttempted;
    private boolean closeFailed;
    private ReadFailure readFailure;
    private byte[] bytes;

    FixedPluginConfigurationResource(Path root, ConfigurationResourceEvidence evidence) {
        this(root, evidence, path -> {
            if (!Files.isDirectory(path.getParent(), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("configuration resource is not a regular file");
            }
            return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
        });
    }

    FixedPluginConfigurationResource(Path root, ConfigurationResourceEvidence evidence,
                                     ResourceIo io) {
        path = root.resolve(ConfigurationResourceEvidence.FILE_NAME);
        this.evidence = evidence;
        this.io = io;
    }

    @Override
    public synchronized byte[] read() {
        if (!readAttempted) {
            if (closeAttempted) {
                throw new IllegalStateException("package configuration resource is closed");
            }
            readAttempted = true;
            if (evidence.kind() == ConfigurationResourceEvidence.Kind.ABSENT) {
                readFailure = ReadFailure.ABSENT;
            } else if (evidence.kind() != ConfigurationResourceEvidence.Kind.REGULAR
                || evidence.length() > MAX_BYTES) {
                readFailure = ReadFailure.READ;
            } else {
                readBytes();
            }
        }
        if (readFailure != null) {
            throw readFailure.exception();
        }
        return bytes.clone();
    }

    private void readBytes() {
        try {
            // Ownership is recorded immediately, before allocation, hashing or reading can fail.
            input = io.open(path);
            var output = new ByteArrayOutputStream();
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer, 0, Math.min(buffer.length, MAX_BYTES + 1 - total))) >= 0) {
                total += count;
                if (total > MAX_BYTES) {
                    readFailure = ReadFailure.READ;
                    return;
                }
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
            }
            if (total != evidence.length()
                || !HexFormat.of().formatHex(digest.digest()).equals(evidence.rawDigest())) {
                readFailure = ReadFailure.IDENTITY;
                return;
            }
            bytes = output.toByteArray();
        } catch (IOException | RuntimeException | Error | NoSuchAlgorithmException failure) {
            readFailure = ReadFailure.READ;
            rethrowFatal(failure);
        }
    }

    @Override
    public synchronized void close() {
        if (!closeAttempted) {
            closeAttempted = true;
            if (input != null) {
                try {
                    input.close();
                    input = null;
                } catch (IOException | RuntimeException | Error failure) {
                    closeFailed = true;
                    rethrowFatal(failure);
                }
            }
        }
        if (closeFailed) {
            throw new ArtifactException(ArtifactPhase.VALIDATE,
                "cannot close package configuration resource", null, null);
        }
    }

    @SuppressWarnings("removal")
    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }

    @FunctionalInterface
    interface ResourceIo {
        InputStream open(Path path) throws IOException;
    }

    private enum ReadFailure {
        ABSENT, READ, IDENTITY;

        RuntimeException exception() {
            return switch (this) {
                case ABSENT -> new NoSuchElementException("package configuration resource is absent");
                case READ -> new ArtifactException(ArtifactPhase.VALIDATE,
                    "cannot read package configuration resource", null, null);
                case IDENTITY -> new ArtifactException(ArtifactPhase.DIGEST,
                    "package configuration resource content identity mismatch", null, null);
            };
        }
    }
}
