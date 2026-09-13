package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.cli.api.CliCommandDescriptor;

import java.util.LinkedHashSet;
import java.util.List;

/** 以捕获的命令 descriptor 识别不得写入历史或诊断的调用输入。 */
final class CliSensitiveInput {
    private CliSensitiveInput() {
    }

    static boolean present(List<CliCommandDescriptor> descriptors, List<String> words) {
        if (containsPath(List.of("tools", "invoke"), words)) return true;
        for (var descriptor : descriptors) {
            if (!containsPath(descriptor.path(), words)) continue;
            for (var option : descriptor.options()) {
                if (!option.sensitive()) continue;
                for (var word : words) {
                    if (option.names().stream().anyMatch(name -> matchesOption(name, word))) return true;
                }
            }
        }
        return false;
    }

    static String redactDiagnostic(String diagnostic, List<CliCommandDescriptor> descriptors,
                                   List<String> words) {
        var values = new LinkedHashSet<String>();
        if (containsPath(List.of("tools", "invoke"), words)) {
            collectOptionValues(List.of("--input"), words, values);
        }
        for (var descriptor : descriptors) {
            if (!containsPath(descriptor.path(), words)) continue;
            descriptor.options().stream().filter(option -> option.sensitive())
                .forEach(option -> collectOptionValues(option.names(), words, values));
        }
        var redacted = diagnostic;
        for (var value : values.stream().sorted((left, right) ->
            Integer.compare(right.length(), left.length())).toList()) {
            redacted = redacted.replace(value, "[敏感值已省略]");
        }
        return redacted;
    }

    private static void collectOptionValues(List<String> names, List<String> words,
                                            LinkedHashSet<String> values) {
        for (var index = 0; index < words.size(); index++) {
            var word = words.get(index);
            for (var name : names) {
                String value = null;
                if (word.equals(name)) {
                    if (index + 1 < words.size()) {
                        value = words.get(index + 1);
                    }
                } else if (word.startsWith(name + "=")) {
                    value = word.substring(name.length() + 1);
                } else if (name.startsWith("-") && !name.startsWith("--")
                    && word.startsWith(name) && word.length() > name.length()) {
                    value = word.substring(name.length());
                }
                if (value != null && !value.isEmpty()) values.add(value);
            }
        }
    }

    private static boolean containsPath(List<String> path, List<String> words) {
        for (var start = 0; start <= words.size() - path.size(); start++) {
            var matches = true;
            for (var index = 0; index < path.size(); index++) {
                if (!path.get(index).equals(words.get(start + index))) {
                    matches = false;
                    break;
                }
            }
            if (matches) return true;
        }
        return false;
    }

    private static boolean matchesOption(String name, String word) {
        if (word.equals(name) || word.startsWith(name + "=")) return true;
        return name.startsWith("-") && !name.startsWith("--") && word.startsWith(name)
            && word.length() > name.length();
    }
}
