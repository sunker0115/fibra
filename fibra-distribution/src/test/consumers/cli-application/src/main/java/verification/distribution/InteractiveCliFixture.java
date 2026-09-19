package verification.distribution;

import com.sstlfsj.fibra.cli.CliSession;
import com.sstlfsj.fibra.cli.api.CliApplication;
import com.sstlfsj.fibra.cli.api.CliBootstrapCommand;
import com.sstlfsj.fibra.cli.api.CliCommandDescriptor;
import com.sstlfsj.fibra.cli.api.CliCommandResult;
import com.sstlfsj.fibra.cli.api.CliInputResult;
import com.sstlfsj.fibra.cli.api.CliProfile;
import com.sstlfsj.fibra.cli.api.CliTerminalControl;
import com.sstlfsj.fibra.cli.api.CliTerminalFrame;
import com.sstlfsj.fibra.cli.api.CliTerminalInput;
import com.sstlfsj.fibra.cli.api.CliTerminalKey;
import com.sstlfsj.fibra.cli.api.CliTerminalRenderer;
import com.sstlfsj.fibra.cli.api.CliTerminalSize;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.engine.DeploymentTargetStore;
import com.sstlfsj.fibra.engine.FibraEngine;

import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 只依赖发布 API 的通用交互 fixture，不包含 Agent 产品语义。 */
public final class InteractiveCliFixture {
    private InteractiveCliFixture() {
    }

    public static void main(String[] arguments) throws Exception {
        var home = Path.of(arguments[0]).toAbsolutePath().normalize();
        var inputMode = arguments.length > 1 && arguments[1].equals("input");
        try (var packages = new PluginPackageStore(home.resolve("packages"));
             var engine = FibraEngine.builder(packages, DeploymentTargetStore.inMemory())
                 .hostTerminationPort(ignored -> { }).build()) {
            engine.startAsync().block();
            var builder = CliApplication.builder("consumer").version("1.0.0");
            if (inputMode) {
                builder.inputHandler(request -> {
                    request.invocation().output().stdout("input:" + request.text());
                    return request.text().equals("/exit")
                        ? CliInputResult.exitWith(CliCommandResult.success())
                        : CliInputResult.continueWith(CliCommandResult.success());
                });
            } else {
                builder.addBootstrapCommand(echoCommand())
                    .addBootstrapCommand(screenCommand())
                    .addBootstrapCommand(failingScreenCommand());
            }
            var application = builder.build();
            try (var session = CliSession.builder(application, engine.published(), profile(home))
                .systemTerminal().historyFile(home.resolve("repl.history")).build()) {
                var notice = Thread.ofPlatform().daemon(true).name("cli-fixture-notice")
                    .start(() -> printNotice(session));
                var status = session.execute("repl");
                notice.join();
                if (status != 0) throw new IllegalStateException("REPL exited with " + status);
            }
        }
    }

    private static CliBootstrapCommand echoCommand() {
        return new CliBootstrapCommand(new CliCommandDescriptor(List.of("echo"), "输出参数。",
            List.of(), "TEXT", List.of()), request -> {
                request.invocation().output().stdout(String.join(" ", request.arguments()));
                return CliCommandResult.success();
            });
    }

    private static CliBootstrapCommand screenCommand() {
        return new CliBootstrapCommand(new CliCommandDescriptor(List.of("screen"),
            "运行通用渐进式终端画面。", List.of(), null, List.of()), request -> {
                var renderer = new VerificationRenderer(
                    () -> request.invocation().output().stdout("screen-ready"));
                try (var lease = request.invocation().terminal().acquire()) {
                    lease.run(renderer);
                    request.invocation().output().stdout(renderer.summary());
                    return CliCommandResult.success();
                } catch (InterruptedIOException expected) {
                    request.invocation().output().stdout("screen-cancelled");
                    throw expected;
                }
            });
    }

    private static CliBootstrapCommand failingScreenCommand() {
        return new CliBootstrapCommand(new CliCommandDescriptor(List.of("fail-screen"),
            "验证 renderer 失败后的终端恢复。", List.of(), null, List.of()), request -> {
                try (var lease = request.invocation().terminal().acquire()) {
                    lease.run(size -> {
                        throw new IllegalStateException("expected-render-failure");
                    });
                }
                return CliCommandResult.success();
            });
    }

    private static void printNotice(CliSession session) {
        try {
            Thread.sleep(750L);
            session.printAbove("background-ready");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    private static CliProfile profile(Path home) {
        return new CliProfile("external", home, home.resolve("config"), home.resolve("plugins"),
            home.resolve("data"));
    }

    private static final class VerificationRenderer implements CliTerminalRenderer {
        private final Runnable started;
        private final List<String> inputs = new ArrayList<>();
        private CliTerminalControl control;
        private CliTerminalSize size;
        private int resizeCount;

        private VerificationRenderer(Runnable started) {
            this.started = started;
        }

        @Override public void start(CliTerminalControl value) {
            control = value;
            started.run();
        }

        @Override public void input(CliTerminalInput input) {
            var key = input.key();
            var event = input.isPaste() ? "PASTE:" + input.text()
                : key.orElseThrow() == CliTerminalKey.CHARACTER
                    ? "CHARACTER:" + input.text() : key.orElseThrow().name();
            if (!input.modifiers().isEmpty()) {
                event += "+" + input.modifiers().stream().map(Enum::name).sorted()
                    .reduce((left, right) -> left + "+" + right).orElseThrow();
            }
            inputs.add(event);
            if (key.filter(value -> value == CliTerminalKey.ENTER).isPresent()) control.finish();
        }

        @Override public void resize(CliTerminalSize value) {
            size = value;
            resizeCount++;
        }

        @Override public CliTerminalFrame render(CliTerminalSize value) {
            return new CliTerminalFrame(List.of("inputs=" + inputs.size() + " size="
                + value.columns() + "x" + value.rows()), Optional.empty());
        }

        private String summary() {
            return "screen-inputs=" + String.join(",", inputs) + " size=" + size.columns() + "x"
                + size.rows() + " resizes=" + resizeCount;
        }
    }
}
