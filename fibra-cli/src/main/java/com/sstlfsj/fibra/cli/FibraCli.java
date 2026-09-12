package com.sstlfsj.fibra.cli;

import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@CommandLine.Command(name = "fibra", mixinStandardHelpOptions = true,
    versionProvider = FibraCli.VersionProvider.class)
public final class FibraCli implements Runnable {
    private FibraCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.in, new PrintWriter(System.out, true), new PrintWriter(System.err, true)));
    }

    public static int run(String[] args, InputStream in, PrintWriter out, PrintWriter err) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        return new CommandLine(new FibraCli()).setOut(out).setErr(err).execute(args);
    }

    @Override
    public void run() {
    }

    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {"fibra " + version()};
        }
    }

    private static String version() {
        var implementationVersion = FibraCli.class.getPackage().getImplementationVersion();
        return implementationVersion == null ? developmentVersion() : implementationVersion;
    }

    private static String developmentVersion() {
        try (var input = FibraCli.class.getResourceAsStream("/META-INF/fibra-cli-version")) {
            if (input != null) return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read CLI version", exception);
        }
        return "unknown";
    }
}
