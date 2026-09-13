package com.sstlfsj.fibra.cli;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import picocli.CommandLine;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** 仅从当前 REPL 行捕获的命令树派生命令候选。 */
final class CliCommandCompleter implements Completer {
    private CommandLine commandLine;
    private List<CommandGeneration.Tool> tools = List.of();

    void commandLine(CommandLine commandLine) {
        this.commandLine = Objects.requireNonNull(commandLine, "commandLine");
    }

    void commandGeneration(CommandGeneration generation) {
        tools = generation == null ? List.of() : generation.tools();
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(candidates, "candidates");
        var root = commandLine;
        if (root == null) return;
        var current = root;
        var words = line.words();
        if (completeToolInvocation(line, candidates, words)) return;
        if (line.wordIndex() == 1 && !words.isEmpty() && words.getFirst().equals("help")) {
            current = root;
        } else {
            for (var index = 0; index < line.wordIndex() && index < words.size(); index++) {
                var child = current.getSubcommands().get(words.get(index));
                if (child == null) break;
                current = child;
            }
        }
        var prefix = line.word();
        var values = new LinkedHashSet<String>();
        if (prefix.startsWith("-")) {
            for (var option : current.getCommandSpec().options()) {
                for (var name : option.names()) add(values, prefix, name);
            }
        } else {
            var previous = line.wordIndex() == 0 || line.wordIndex() > words.size() - 1
                ? null : words.get(line.wordIndex() - 1);
            var option = previous == null ? null : current.getCommandSpec().options().stream()
                .filter(candidate -> java.util.Arrays.asList(candidate.names()).contains(previous))
                .findFirst().orElse(null);
            if (option != null && option.completionCandidates() != null) {
                option.completionCandidates().forEach(value -> add(values, prefix, value.toString()));
            } else {
                for (var name : current.getSubcommands().keySet()) add(values, prefix, name);
                for (var positional : current.getCommandSpec().positionalParameters()) {
                    var completionCandidates = positional.completionCandidates();
                    if (completionCandidates != null) completionCandidates.forEach(value ->
                        add(values, prefix, value.toString()));
                }
            }
        }
        values.forEach(value -> candidates.add(new Candidate(value)));
    }

    private boolean completeToolInvocation(ParsedLine line, List<Candidate> candidates,
                                           List<String> words) {
        if (words.size() < 2 || !words.get(0).equals("tools") || !words.get(1).equals("invoke")) {
            return false;
        }
        var values = new LinkedHashSet<String>();
        if (line.wordIndex() == 2) {
            tools.stream().map(CommandGeneration.Tool::provider).forEach(value ->
                add(values, line.word(), value));
        } else if (line.wordIndex() == 3 && words.size() >= 3) {
            var provider = words.get(2);
            tools.stream().filter(tool -> tool.provider().equals(provider))
                .map(CommandGeneration.Tool::name).forEach(value -> add(values, line.word(), value));
        } else {
            return false;
        }
        values.forEach(value -> candidates.add(new Candidate(value)));
        return true;
    }

    private static void add(LinkedHashSet<String> values, String prefix, String value) {
        if (value.startsWith(prefix)) values.add(value);
    }
}
