package verification.distribution;

import com.sstlfsj.fibra.cli.CliSession;
import com.sstlfsj.fibra.cli.api.CliApplication;
import com.sstlfsj.fibra.cli.api.CliBootstrapCommand;
import com.sstlfsj.fibra.cli.api.CliCommandDescriptor;
import com.sstlfsj.fibra.cli.api.CliCommandResult;
import com.sstlfsj.fibra.cli.api.CliProfile;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.engine.FibraEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliConsumerTest {
    @Test
    void embedsMultipleFiniteInvocationsWithoutOwningThePublishedRuntime(@TempDir Path home) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        var application = CliApplication.builder("consumer").version("1.0.0")
            .addBootstrapCommand(new CliBootstrapCommand(new CliCommandDescriptor(
                List.of("echo"), "输出参数。", List.of(), "TEXT", List.of()), request -> {
                    request.invocation().output().stdout(String.join(" ", request.arguments()));
                    return CliCommandResult.success();
                })).build();

        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty()).build()) {
            engine.start().block();
            var session = CliSession.builder(application, engine.published(), profile(home))
                .streams(new ByteArrayInputStream(new byte[0]), writer(stdout), writer(stderr))
                .historyFile(home.resolve("history/repl.history"))
                .build();

            assertEquals(0, session.execute("echo", "first"));
            assertEquals(0, session.execute("echo", "second"));
            assertTrue(session.printAbove("background-ready"));
            session.close();

            assertEquals("first\nsecond\n", stdout.toString(StandardCharsets.UTF_8));
            assertEquals("background-ready\n", stderr.toString(StandardCharsets.UTF_8));
            assertFalse(engine.published().current().viewRevision().isBlank());
            assertFalse(session.printAbove("too-late"));
            assertThrows(IllegalStateException.class, () -> session.execute("echo", "late"));
        }
    }

    private static CliProfile profile(Path home) {
        return new CliProfile("external", home, home.resolve("config"), home.resolve("plugins"),
            home.resolve("data"));
    }

    private static PrintWriter writer(ByteArrayOutputStream target) {
        return new PrintWriter(target, true, StandardCharsets.UTF_8);
    }
}
