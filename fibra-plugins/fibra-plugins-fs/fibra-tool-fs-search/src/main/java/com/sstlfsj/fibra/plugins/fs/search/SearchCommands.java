package com.sstlfsj.fibra.plugins.fs.search;

import java.util.ArrayList;
import java.util.List;

final class SearchCommands {
    private static final List<String> VCS_DIRECTORIES =
        List.of(".git", ".svn", ".hg", ".bzr", ".jj", ".sl");

    private SearchCommands() {
    }

    static List<String> glob(String pattern, String path) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("pattern must not be blank");
        }
        requireOptionalText(path, "path");
        var arguments = new ArrayList<>(List.of(
            "--files", "--glob=" + pattern, "--sort=modified", "--no-ignore", "--hidden"));
        for (var directory : VCS_DIRECTORIES) {
            arguments.add("--glob=!**/" + directory);
            arguments.add("--glob=!**/" + directory + "/**");
        }
        addPath(arguments, path);
        return List.copyOf(arguments);
    }

    static List<String> grep(String pattern, String path, String include) {
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("pattern must not be empty");
        }
        requireOptionalText(path, "path");
        validateInclude(include);
        var arguments = new ArrayList<>(List.of("--json", "--regexp=" + pattern));
        if (include != null) {
            arguments.add("--glob=" + include);
        }
        addPath(arguments, path);
        return List.copyOf(arguments);
    }

    private static void validateInclude(String include) {
        requireOptionalText(include, "include");
        if (include == null) {
            return;
        }
        if (include.startsWith("!")) {
            throw new IllegalArgumentException("include must be a positive glob");
        }
        var depth = 0;
        for (var index = 0; index < include.length(); index++) {
            switch (include.charAt(index)) {
                case '{' -> depth++;
                case '}' -> depth = Math.max(0, depth - 1);
                case ',' -> {
                    if (depth == 0) {
                        throw new IllegalArgumentException("include must be one glob");
                    }
                }
                default -> {
                }
            }
        }
    }

    private static void requireOptionalText(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void addPath(List<String> arguments, String path) {
        if (path != null) {
            arguments.add("--");
            arguments.add(path);
        }
    }
}
