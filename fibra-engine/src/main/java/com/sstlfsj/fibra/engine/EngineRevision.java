package com.sstlfsj.fibra.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;

final class EngineRevision {
    private EngineRevision() { }

    static String compute(Collection<RevisionArtifact> artifacts,
                          Collection<Path> configPaths) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            artifacts.stream().sorted(java.util.Comparator.comparing(RevisionArtifact::id))
                .forEach(artifact -> update(digest, "artifact\0" + artifact.id() + '\0'
                    + artifact.version() + '\0' + artifact.sha256() + '\n'));
            for (var path : configPaths.stream()
                .map(candidate -> candidate.toAbsolutePath().normalize()).sorted().toList()) {
                update(digest, "config\0" + path + '\0');
                if (Files.isRegularFile(path)) {
                    digest.update(Files.readAllBytes(path));
                } else {
                    update(digest, "<missing>");
                }
                digest.update((byte) '\n');
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot calculate Fibra engine revision", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }
}
