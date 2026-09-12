package com.sstlfsj.fibra.plugins.shell.tool;
import com.sstlfsj.fibra.*;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.plugins.shell.*;
import com.sstlfsj.fibra.plugins.tool.*;
import com.sstlfsj.fibra.value.LiteralValue;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ShellToolEntrypoint implements PluginEntrypoint<Void> {
    private static final long MAX_TIMER_DELAY_MILLIS = Integer.MAX_VALUE;
    @Override public PluginDefinition<Void> definition() {
        return PluginDefinition.builder("fibra-tool-shell", Void.class,
            () -> (context, config) -> context.services().require(ContributionServices.REGISTRAR)
                .register(context, ToolContributions.KIND, context.plugins().current().orElseThrow().id(),
                    "bash", descriptor(), ShellToolEntrypoint::invoke).then())
            .require(ContributionServices.REGISTRAR).require(ShellServices.SHELL).build();
    }

    private static Mono<ToolResult> invoke(InvocationContext invocation, ToolRequest input) {
        return Mono.defer(() -> {
            final ShellRequest request;
            try {
                var args = input.arguments().values();
                if (!java.util.Set.of("command", "workdir", "timeoutMs").containsAll(args.keySet())) {
                    throw new IllegalArgumentException("unsupported shell argument");
                }
                if (!(args.get("timeoutMs") instanceof LiteralValue.NumberValue number)) {
                    throw new IllegalArgumentException("timeoutMs must be a positive integer");
                }
                long timeoutMillis = number.value().longValueExact();
                if (timeoutMillis < 1 || timeoutMillis > MAX_TIMER_DELAY_MILLIS) {
                    throw new IllegalArgumentException(
                        "timeoutMs must be between 1 and " + MAX_TIMER_DELAY_MILLIS);
                }
                request = ShellRequest.builder().command(text(args.get("command"), "command"))
                    .workdir(text(args.get("workdir"), "workdir"))
                    .timeout(Duration.ofMillis(timeoutMillis)).build();
            } catch (IllegalArgumentException | ArithmeticException error) {
                return Mono.error(new ToolException(ToolFailureCode.INVALID_ARGUMENT, error.getMessage(), error));
            }
            return invocation.withCancellation(input.cancellation()).service(ShellServices.SHELL)
                .invoke((caller, shell) -> shell.run(caller, request))
                .map(result -> {
                    if (result.timedOut()) throw new ToolException(ToolFailureCode.TIMEOUT, "Shell command timed out");
                    if (result.aborted()) throw new ToolException(ToolFailureCode.ABORTED, "Shell command was cancelled");
                    var data = new LinkedHashMap<String, Object>();
                    data.put("exitCode", result.exitCode());
                    data.put("signal", result.signal());
                    data.put("timedOut", result.timedOut());
                    data.put("aborted", result.aborted());
                    data.put("timeoutMs", result.timeout().toMillis());
                    data.put("stdout", output(result.stdout()));
                    data.put("stderr", output(result.stderr()));
                    return ToolResult.textAndStructured(ShellResultFormatter.format(result), LiteralValue.of(data));
                })
                .onErrorMap(ShellException.class, error -> new ToolException(
                    error.code() == ShellErrorCode.TERMINATION_FAILED
                        ? ToolFailureCode.TERMINATION_FAILED : ToolFailureCode.START_FAILED,
                    error.getMessage(), error));
        });
    }

    private static String text(LiteralValue value, String field) {
        if (!(value instanceof LiteralValue.StringValue string) || string.value().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return string.value();
    }

    private static Map<String, Object> output(ShellOutput output) {
        return Map.of("text", output.text(), "truncated", output.truncated(), "totalBytes", output.totalBytes());
    }

    private static ToolDescriptor descriptor() {
        var output = Map.of("type", "object", "required", List.of("text", "truncated", "totalBytes"),
            "additionalProperties", false, "properties", Map.of(
                "text", Map.of("type", "string"), "truncated", Map.of("type", "boolean"),
                "totalBytes", Map.of("type", "integer", "minimum", 0)));
        return new ToolDescriptor("Bash", "Execute a fresh bash command in an explicit working directory.",
            object(Map.of("type", "object", "additionalProperties", false,
                "required", List.of("command", "workdir", "timeoutMs"), "properties", Map.of(
                    "command", Map.of("type", "string", "minLength", 1),
                    "workdir", Map.of("type", "string", "minLength", 1),
                    "timeoutMs", Map.of("type", "integer", "minimum", 1,
                        "maximum", MAX_TIMER_DELAY_MILLIS)))),
            object(Map.of("type", "object", "additionalProperties", false,
                "required", List.of("exitCode", "signal", "timedOut", "aborted", "timeoutMs", "stdout", "stderr"),
                "properties", Map.of(
                    "exitCode", Map.of("type", List.of("integer", "null")),
                    "signal", Map.of("type", List.of("string", "null")),
                    "timedOut", Map.of("type", "boolean"), "aborted", Map.of("type", "boolean"),
                    "timeoutMs", Map.of("type", "integer"), "stdout", output, "stderr", output))));
    }

    private static LiteralValue.ObjectValue object(Map<String, ?> value) {
        return (LiteralValue.ObjectValue) LiteralValue.of(value);
    }
}
