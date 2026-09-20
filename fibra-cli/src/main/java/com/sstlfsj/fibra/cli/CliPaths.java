package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.value.LiteralValue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

record CliPaths(Path home, String profile, Path configRoot, Path pluginsRoot,
                Path dataRoot, Path nodeExecutable, Path rgExecutable,
                Path bashExecutable) {
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
        rgExecutable = Objects.requireNonNull(rgExecutable, "rgExecutable");
        bashExecutable = Objects.requireNonNull(bashExecutable, "bashExecutable");
    }

    static CliPaths resolve(Path home, String profile, Path configRoot,
                            Path pluginsRoot, Path dataRoot, Path nodeExecutable) {
        var resolvedHome = absolute(home, "home");
        return new CliPaths(resolvedHome, profile,
            configRoot == null ? resolvedHome.resolve("config") : configRoot,
            pluginsRoot == null ? resolvedHome.resolve("plugins") : pluginsRoot,
            dataRoot == null ? resolvedHome.resolve("data") : dataRoot,
            nodeExecutable == null ? bundledOr(resolvedHome, "node") : nodeExecutable,
            bundledOr(resolvedHome, "rg"), bundledOr(resolvedHome, "bash"));
    }

    Path profileFile() {
        return configRoot.resolve("profiles").resolve(profile + ".yaml");
    }

    Path profilePackagesFile() {
        return configRoot.resolve("profiles").resolve(profile + ".packages.yaml");
    }

    Path profileData() {
        return dataRoot.resolve("profiles").resolve(profile);
    }

    Path stateRoot() {
        return profileData().resolve("state");
    }

    Path packageStoreRoot() {
        return profileData().resolve("packages");
    }

    Path auditFile() {
        return profileData().resolve("audit.log");
    }

    Path nodeSessionRoot() {
        return profileData().resolve("node-sessions");
    }

    Path workspaceRoot() {
        return profileData().resolve("workspace");
    }

    Path storageRoot() {
        return profileData().resolve("storage");
    }

    Path replHistoryFile() {
        return profileData().resolve("repl.history");
    }

    ConfigContextSnapshot configContext() {
        return ConfigContextSnapshot.of((LiteralValue.ObjectValue) LiteralValue.of(
            Map.of("fibra", Map.of(
                "home", home.toString(),
                "dataRoot", dataRoot.toString(),
                "workspaceRoot", workspaceRoot().toString(),
                "storageRoot", storageRoot().toString(),
                "nodeExecutable", nodeExecutable.toString(),
                "rgExecutable", rgExecutable.toString(),
                "bashExecutable", bashExecutable.toString()))));
    }

    private static Path bundledOr(Path home, String name) {
        var bundled = home.resolve("runtime/bin").resolve(name);
        return Files.isRegularFile(bundled) ? bundled : Path.of(name);
    }

    private static Path absolute(Path value, String name) {
        return Objects.requireNonNull(value, name).toAbsolutePath().normalize();
    }
}
