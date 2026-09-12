package com.sstlfsj.fibra.cli;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

record CliPaths(Path home, String profile, Path configRoot, Path pluginsRoot,
                Path dataRoot, Path nodeExecutable) {
    private static final Pattern PROFILE = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    CliPaths {
        home = absolute(home, "home");
        if (profile == null || !PROFILE.matcher(profile).matches()) {
            throw new IllegalArgumentException("profile must be a safe name of at most 64 characters");
        }
        configRoot = absolute(configRoot, "configRoot");
        pluginsRoot = absolute(pluginsRoot, "pluginsRoot");
        dataRoot = absolute(dataRoot, "dataRoot");
        nodeExecutable = Objects.requireNonNull(nodeExecutable, "nodeExecutable");
    }

    static CliPaths resolve(Path home, String profile, Path configRoot,
                            Path pluginsRoot, Path dataRoot, Path nodeExecutable) {
        var resolvedHome = absolute(home, "home");
        return new CliPaths(resolvedHome, profile,
            configRoot == null ? resolvedHome.resolve("config") : configRoot,
            pluginsRoot == null ? resolvedHome.resolve("plugins") : pluginsRoot,
            dataRoot == null ? resolvedHome.resolve("data") : dataRoot,
            nodeExecutable == null ? Path.of("node") : nodeExecutable);
    }

    Path profileFile() {
        return configRoot.resolve("profiles").resolve(profile + ".yaml");
    }

    Path profileData() {
        return dataRoot.resolve("profiles").resolve(profile);
    }

    Path stateRoot() {
        return profileData().resolve("state");
    }

    Path artifactRoot() {
        return profileData().resolve("artifacts");
    }

    Path auditFile() {
        return profileData().resolve("audit.log");
    }

    Path nodeSessionRoot() {
        return profileData().resolve("node-sessions");
    }

    private static Path absolute(Path value, String name) {
        return Objects.requireNonNull(value, name).toAbsolutePath().normalize();
    }
}
