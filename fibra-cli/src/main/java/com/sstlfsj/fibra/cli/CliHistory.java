package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.cli.api.CliCommandDescriptor;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** REPL 的私有历史存储；只向 JLine 提供可安全重放的条目。 */
final class CliHistory extends DefaultHistory {
    static final String REDACTED_COMMAND = "[敏感命令已省略]";

    private final Path file;
    private List<CliCommandDescriptor> descriptors = List.of();

    CliHistory(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    void savePersisted() throws IOException {
        Files.createDirectories(file.getParent());
        write(file, false);
    }

    void commandDescriptors(List<CliCommandDescriptor> value) {
        descriptors = List.copyOf(value);
    }

    @Override
    public void add(Instant time, String line) {
        super.add(time, redact(Objects.requireNonNull(line, "line")));
    }

    private String redact(String line) {
        try {
            var words = new DefaultParser().parse(line, line.length()).words();
            if (CliSensitiveInput.present(descriptors, words)) return REDACTED_COMMAND;
        } catch (RuntimeException ignored) {
            return REDACTED_COMMAND;
        }
        return line;
    }
}
