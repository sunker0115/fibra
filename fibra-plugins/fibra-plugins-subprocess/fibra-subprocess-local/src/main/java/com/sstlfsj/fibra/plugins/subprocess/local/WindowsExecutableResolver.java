package com.sstlfsj.fibra.plugins.subprocess.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Resolves a Windows application name without changing the caller's command-line argv. */
final class WindowsExecutableResolver implements WindowsExecutableLookup {
    private final Map<String, String> environment;
    private final Map<String, String> currentEnvironment;
    private final CandidateAccess candidates;

    WindowsExecutableResolver(Map<String, String> environment,
                              Map<String, String> currentEnvironment,
                              CandidateAccess candidates) {
        this.environment = Map.copyOf(environment);
        this.currentEnvironment = Map.copyOf(currentEnvironment);
        this.candidates = candidates;
    }

    static WindowsExecutableResolver system() {
        var environment = System.getenv();
        return new WindowsExecutableResolver(environment, environment, new CandidateAccess() {
            @Override public boolean exists(String candidate) {
                try {
                    var path = Path.of(candidate);
                    if (Files.isDirectory(path)) return false;
                    return Files.isRegularFile(path) || Files.isSymbolicLink(path);
                } catch (RuntimeException failure) {
                    return false;
                }
            }

            @Override public String absolute(String candidate) {
                return Path.of(candidate).toAbsolutePath().normalize().toString();
            }
        });
    }

    @Override
    public String resolve(String command, String cwd) throws IOException {
        int nameStart = fileNameStart(command);
        String directory = command.substring(0, nameStart);
        String name = command.substring(nameStart);
        var roots = new ArrayList<String>();
        if (nameStart != 0) {
            roots.add(directory);
        } else {
            if (environmentValue(currentEnvironment,
                "NODEFAULTCURRENTDIRECTORYINEXEPATH") == null) {
                roots.add("");
            }
            String path = environmentValue(environment, "PATH");
            if (path == null) path = environmentValue(currentEnvironment, "PATH");
            roots.addAll(pathDirectories(path == null ? "" : path));
        }
        for (var root : roots) {
            String base = searchPathJoin(root, name, cwd);
            for (var candidate : executableNames(base, name)) {
                if (candidates.exists(candidate)) return candidates.absolute(candidate);
            }
        }
        throw new IOException("Windows executable not found: " + command);
    }

    private static String environmentValue(Map<String, String> environment, String name) {
        return environment.entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase(name))
            .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static List<String> pathDirectories(String path) {
        var directories = new ArrayList<String>();
        int start = 0;
        while (start < path.length()) {
            if (path.charAt(start) == ';') {
                start++;
                continue;
            }
            char quote = path.charAt(start);
            boolean quoted = quote == '"' || quote == '\'';
            int quoteEnd = quoted ? path.indexOf(quote, start + 1) : -1;
            int separator = path.indexOf(';', quoted && quoteEnd < 0 ? path.length()
                : quoted ? quoteEnd : start);
            int end = separator < 0 ? path.length() : separator;
            String directory = path.substring(start, end);
            if (directory.startsWith("\"") || directory.startsWith("'")) {
                directory = directory.substring(1);
            }
            if (directory.endsWith("\"") || directory.endsWith("'")) {
                directory = directory.substring(0, directory.length() - 1);
            }
            if (!directory.isEmpty()) directories.add(directory);
            start = end + 1;
        }
        return directories;
    }

    private static int fileNameStart(String command) {
        int start = command.length();
        while (start > 0) {
            char character = command.charAt(start - 1);
            if (character == '\\' || character == '/' || character == ':') break;
            start--;
        }
        return start;
    }

    private static String searchPathJoin(String directory, String name, String cwd) {
        String prefix = cwd;
        String adjustedDirectory = directory;
        if (directory.length() > 2 && slash(directory.charAt(0)) && slash(directory.charAt(1))) {
            prefix = "";
        } else if (!directory.isEmpty() && slash(directory.charAt(0))) {
            prefix = cwd.substring(0, Math.min(2, cwd.length()));
        } else if (directory.length() >= 2 && directory.charAt(1) == ':'
            && (directory.length() < 3 || !slash(directory.charAt(2)))) {
            if (cwd.length() < 2 || !cwd.substring(0, 2)
                .equalsIgnoreCase(directory.substring(0, 2))) {
                prefix = "";
            } else {
                adjustedDirectory = directory.substring(2);
            }
        } else if (directory.length() > 2 && directory.charAt(1) == ':') {
            prefix = "";
        }
        return append(append(prefix, adjustedDirectory), name);
    }

    private static boolean slash(char value) {
        return value == '\\' || value == '/';
    }

    private static String append(String base, String part) {
        if (base.isEmpty() || part.isEmpty()) return base + part;
        char last = base.charAt(base.length() - 1);
        return last == '\\' || last == '/' || last == ':' ? base + part : base + "\\" + part;
    }

    private static List<String> executableNames(String command, String name) {
        int dot = name.indexOf('.');
        boolean hasExtension = dot >= 0 && dot < name.length() - 1;
        String separator = name.endsWith(".") ? "" : ".";
        var names = new ArrayList<String>();
        if (hasExtension) names.add(command);
        names.add(command + separator + "com");
        names.add(command + separator + "exe");
        return names;
    }

    interface CandidateAccess {
        boolean exists(String candidate);

        String absolute(String candidate);
    }
}

@FunctionalInterface
interface WindowsExecutableLookup {
    String resolve(String command, String cwd) throws IOException;
}
