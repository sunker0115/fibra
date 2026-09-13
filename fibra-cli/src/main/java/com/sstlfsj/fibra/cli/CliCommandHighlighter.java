package com.sstlfsj.fibra.cli;

import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.reader.impl.DefaultParser;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import picocli.CommandLine;

import java.util.Objects;

/** 仅按当前 REPL 行捕获的命令树标记已知命令与选项。 */
final class CliCommandHighlighter implements Highlighter {
    private static final AttributedStyle COMMAND = AttributedStyle.DEFAULT
        .foreground(AttributedStyle.CYAN).bold();
    private static final AttributedStyle OPTION = AttributedStyle.DEFAULT
        .foreground(AttributedStyle.BLUE);

    private CommandLine commandLine;

    void commandLine(CommandLine commandLine) {
        this.commandLine = Objects.requireNonNull(commandLine, "commandLine");
    }

    @Override
    public AttributedString highlight(LineReader reader, String buffer) {
        Objects.requireNonNull(buffer, "buffer");
        var root = commandLine;
        if (root == null) return new AttributedString(buffer);
        final java.util.List<String> words;
        try {
            words = new DefaultParser().parse(buffer, buffer.length()).words();
        } catch (RuntimeException ignored) {
            return new AttributedString(buffer);
        }
        var builder = new AttributedStringBuilder();
        var current = root;
        var cursor = 0;
        for (var word : words) {
            var start = buffer.indexOf(word, cursor);
            if (start < 0) return new AttributedString(buffer);
            builder.append(buffer, cursor, start);
            var child = current.getSubcommands().get(word);
            if (child != null) {
                builder.append(word, COMMAND);
                current = child;
            } else if (current.getCommandSpec().options().stream()
                .anyMatch(option -> java.util.Arrays.asList(option.names()).contains(word))) {
                builder.append(word, OPTION);
            } else {
                builder.append(word);
            }
            cursor = start + word.length();
        }
        builder.append(buffer, cursor, buffer.length());
        return (AttributedString) builder.subSequence(0, builder.length());
    }
}
