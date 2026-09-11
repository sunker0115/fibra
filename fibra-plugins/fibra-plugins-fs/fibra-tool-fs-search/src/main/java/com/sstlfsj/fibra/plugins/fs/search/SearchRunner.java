package com.sstlfsj.fibra.plugins.fs.search;

import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.plugins.subprocess.ProcessUnit;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessException;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessOutcome;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessServices;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessSpec;
import com.sstlfsj.fibra.plugins.tool.ToolException;
import com.sstlfsj.fibra.plugins.tool.ToolFailureCode;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
import com.sstlfsj.fibra.plugins.tool.ToolResult;
import com.sstlfsj.fibra.plugins.tool.ToolServices;
import com.sstlfsj.fibra.value.LiteralValue;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

final class SearchRunner {
    private final SearchPluginConfig config;

    SearchRunner(SearchPluginConfig config) {
        this.config = config;
    }

    Mono<ToolResult> glob(InvocationContext context, ToolRequest request) {
        return Mono.defer(() -> {
            var arguments = arguments(request, Set.of("pattern", "path"));
            var pattern = requiredText(arguments, "pattern");
            var path = optionalText(arguments, "path");
            return run(context, SearchCommands.glob(pattern, path), "glob")
                .flatMap(outcome -> {
                    if (outcome.exitCode() == 1) {
                        return Mono.just(result("No files found", Map.of(
                            "paths", List.of(), "seen", 0, "truncated", false)));
                    }
                    var paths = SearchOutputParser.glob(outcome.stdout().text()).stream()
                        .map(this::displayPath).toList();
                    return globResult(context, paths);
                });
        }).onErrorMap(IllegalArgumentException.class, SearchRunner::invalidArgument);
    }

    Mono<ToolResult> grep(InvocationContext context, ToolRequest request) {
        return Mono.defer(() -> {
            var arguments = arguments(request, Set.of("pattern", "path", "include"));
            var pattern = requiredText(arguments, "pattern");
            var path = optionalText(arguments, "path");
            var include = optionalText(arguments, "include");
            return run(context, SearchCommands.grep(pattern, path, include), "grep")
                .flatMap(outcome -> {
                    if (outcome.exitCode() == 1) {
                        return Mono.just(result("No matches found", Map.of(
                            "matches", List.of(), "seen", 0, "truncated", false)));
                    }
                    var matches = SearchOutputParser.grep(outcome.stdout().text()).stream()
                        .map(match -> new SearchMatch(displayPath(match.path()),
                            match.lineNumber(), preview(match.line(), config.limits().grepMaxLineBytes())))
                        .toList();
                    return grepResult(context, matches);
                });
        }).onErrorMap(IllegalArgumentException.class, SearchRunner::invalidArgument);
    }

    private Mono<SubprocessOutcome> run(InvocationContext context, List<String> arguments,
                                        String toolName) {
        if (context.cancellation().isCancelled()) {
            return Mono.error(new ToolException(ToolFailureCode.ABORTED,
                toolName + " was aborted before start"));
        }
        var argv = new ArrayList<String>();
        argv.add(config.rgExecutable());
        argv.add("--no-config");
        argv.addAll(arguments);
        var spec = SubprocessSpec.builder()
            .argv(argv)
            .cwd(config.workdir())
            .stdoutMaxBytes(config.limits().rawOutputMaxBytes())
            .stderrMaxBytes(config.limits().stderrMaxBytes())
            .grace(Duration.ofMillis(config.timing().graceMillis()))
            .build();
        var acquired = new AtomicReference<ProcessUnit>();
        var operation = Mono.defer(() -> context.service(SubprocessServices.SUBPROCESS)
                .invoke((serviceContext, subprocess) -> Mono.defer(
                        () -> subprocess.spawn(serviceContext, spec))
                    .onErrorMap(failure -> serviceContext.cancellation().isCancelled()
                        ? new ControlFailure(ToolFailureCode.ABORTED,
                            toolName + " was aborted before completion")
                        : subprocessFailure(toolName, failure))
                    .doOnNext(acquired::set)
                    .flatMap(unit -> await(serviceContext, unit, toolName))))
            .flatMap(outcome -> validateOutcome(toolName, outcome));
        return operation
            .timeout(Duration.ofMillis(config.timing().timeoutMillis()),
                Mono.error(new ControlFailure(ToolFailureCode.TIMEOUT,
                    toolName + " timed out")))
            .onErrorResume(ControlFailure.class,
                failure -> terminate(acquired.get(), failure.code, failure.getMessage()))
            .onErrorResume(failure -> !(failure instanceof ToolException),
                failure -> terminate(acquired.get(), ToolFailureCode.SEARCH_FAILED,
                    toolName + " subprocess failed", failure))
            .doFinally(signal -> {
                if (signal == SignalType.CANCEL && acquired.get() != null) {
                    acquired.get().terminate();
                }
            });
    }

    private Mono<SubprocessOutcome> await(InvocationContext context, ProcessUnit unit,
                                          String toolName) {
        var completed = unit.done()
            .flatMap(outcome -> unit.waitForExit().thenReturn(outcome))
            .flatMap(outcome -> context.cancellation().isCancelled()
                ? Mono.error(new ControlFailure(ToolFailureCode.ABORTED,
                    toolName + " was aborted before completion"))
                : Mono.just(outcome));
        var cancelled = context.cancellation().cancelled()
            .then(Mono.<SubprocessOutcome>error(new ControlFailure(
                ToolFailureCode.ABORTED, toolName + " was aborted before completion")));
        return Mono.firstWithSignal(cancelled, completed);
    }

    private Mono<SubprocessOutcome> terminate(ProcessUnit unit, ToolFailureCode code,
                                               String message) {
        return terminate(unit, code, message, null);
    }

    private Mono<SubprocessOutcome> terminate(ProcessUnit unit, ToolFailureCode code,
                                               String message, Throwable cause) {
        if (unit == null) {
            return Mono.error(cause == null
                ? new ToolException(code, message)
                : new ToolException(code, message, cause));
        }
        return Mono.fromRunnable(unit::terminate)
            .then(unit.waitForExit())
            .onErrorMap(failure -> new ToolException(ToolFailureCode.TERMINATION_FAILED,
                "search process tree could not be terminated", failure))
            .then(Mono.error(cause == null
                ? new ToolException(code, message)
                : new ToolException(code, message, cause)));
    }

    private Mono<SubprocessOutcome> validateOutcome(String toolName,
                                                    SubprocessOutcome outcome) {
        if (outcome.signal() != null) {
            return Mono.error(new ToolException(ToolFailureCode.SEARCH_FAILED,
                toolName + " was killed by signal " + outcome.signal()));
        }
        if (outcome.stdout().truncated()
            || outcome.stdout().totalBytes() > config.limits().rawOutputMaxBytes()) {
            return Mono.error(new ToolException(ToolFailureCode.OUTPUT_LIMIT,
                toolName + " raw output exceeded the configured limit"));
        }
        if (outcome.exitCode() == 0 || outcome.exitCode() == 1) {
            return Mono.just(outcome);
        }
        var stderr = outcome.stderr().text().trim();
        if (stderr.toLowerCase().contains("regex parse error")
            || stderr.toLowerCase().contains("error parsing glob")) {
            return Mono.error(new ToolException(ToolFailureCode.INVALID_ARGUMENT,
                toolName + " pattern was rejected by ripgrep: " + stderr));
        }
        return Mono.error(new ToolException(ToolFailureCode.SEARCH_FAILED,
            toolName + " failed with exit " + outcome.exitCode()
                + (stderr.isEmpty() ? "" : ": " + stderr)));
    }

    private Mono<ToolResult> globResult(InvocationContext context, List<String> paths) {
        var kept = paths.stream().limit(config.limits().globMaxResults()).toList();
        var truncated = kept.size() < paths.size();
        var body = kept.isEmpty() ? "" : "\n\n" + String.join("\n", kept);
        var text = truncated
            ? "Found " + kept.size() + " of " + paths.size() + " files" + body
            : "Found " + paths.size() + (paths.size() == 1 ? " file" : " files") + body;
        var data = new LinkedHashMap<String, Object>();
        data.put("paths", kept);
        data.put("seen", paths.size());
        data.put("truncated", truncated);
        if (!truncated) {
            return Mono.just(result(text, data));
        }
        return spill(context, "glob", "glob-results.txt", String.join("\n", paths))
            .map(locator -> result(text + recovery("glob", locator), withSpill(data, locator)));
    }

    private Mono<ToolResult> grepResult(InvocationContext context, List<SearchMatch> matches) {
        var kept = matches.stream().limit(config.limits().grepMaxMatches()).toList();
        var truncated = kept.size() < matches.size();
        var body = formatMatches(kept);
        var text = truncated
            ? "Found " + kept.size() + " of " + matches.size() + " matches"
            : "Found " + matches.size() + (matches.size() == 1 ? " match" : " matches");
        if (!body.isEmpty()) {
            text += "\n\n" + body;
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("matches", kept.stream().map(match -> Map.of(
            "path", match.path(), "lineNumber", match.lineNumber(), "line", match.line())).toList());
        data.put("seen", matches.size());
        data.put("truncated", truncated);
        if (!truncated) {
            return Mono.just(result(text, data));
        }
        var complete = "Found " + matches.size() + " matches\n\n" + formatMatches(matches);
        var base = text;
        return spill(context, "grep", "grep-results.txt", complete)
            .map(locator -> result(base + recovery("grep", locator), withSpill(data, locator)));
    }

    private Mono<String> spill(InvocationContext context, String toolName, String name,
                               String content) {
        var store = context.caller().services().find(ToolServices.RESULT_SPILL_STORE);
        var saved = store.isEmpty()
            ? Mono.just("")
            : Mono.defer(() -> store.orElseThrow().store(context, name, content))
                .onErrorReturn("");
        return saved.flatMap(locator -> context.cancellation().isCancelled()
            ? Mono.error(new ToolException(ToolFailureCode.ABORTED,
                toolName + " was aborted before completion"))
            : Mono.just(locator));
    }

    private static String recovery(String tool, String locator) {
        return locator.isEmpty()
            ? "\n\n(The complete result could not be saved; narrow the " + tool + " search.)"
            : "\n\n(Full " + tool + " result stored at: " + locator + ")";
    }

    private static Map<String, Object> withSpill(Map<String, Object> data, String locator) {
        if (!locator.isEmpty()) {
            data.put("spill", locator);
        }
        return data;
    }

    private String displayPath(String value) {
        var path = Path.of(value);
        if (!path.isAbsolute()) {
            return value;
        }
        var root = Path.of(config.workdir()).toAbsolutePath().normalize();
        var normalized = path.normalize();
        return normalized.startsWith(root) ? root.relativize(normalized).toString() : value;
    }

    private static String formatMatches(List<SearchMatch> matches) {
        var groups = new LinkedHashMap<String, List<SearchMatch>>();
        matches.forEach(match -> groups.computeIfAbsent(match.path(), ignored ->
            new ArrayList<>()).add(match));
        var sections = new ArrayList<String>();
        groups.forEach((path, values) -> sections.add(path + "\n" + values.stream()
            .map(match -> "Line " + match.lineNumber() + ": " + match.line())
            .reduce((left, right) -> left + "\n" + right).orElse("")));
        return String.join("\n\n", sections);
    }

    private static String preview(String value, int maxBytes) {
        var bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        var end = Math.min(maxBytes, bytes.length);
        while (end > 0 && (bytes[end] & 0xc0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8)
            + " (line truncated)";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(ToolRequest request, Set<String> allowed) {
        var values = (Map<String, Object>) request.arguments().toJava();
        for (var key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw new ToolException(ToolFailureCode.INVALID_ARGUMENT,
                    "unsupported argument: " + key);
            }
        }
        return values;
    }

    private static String requiredText(Map<String, Object> arguments, String name) {
        var value = arguments.get(name);
        if (value instanceof String text) {
            return text;
        }
        throw new ToolException(ToolFailureCode.INVALID_ARGUMENT,
            name + " must be text");
    }

    private static String optionalText(Map<String, Object> arguments, String name) {
        var value = arguments.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        throw new ToolException(ToolFailureCode.INVALID_ARGUMENT,
            name + " must be text");
    }

    private static ToolResult result(String text, Map<String, ?> data) {
        return new ToolResult(text, LiteralValue.of(data));
    }

    private static ToolException subprocessFailure(String toolName, Throwable failure) {
        if (failure instanceof ToolException tool) {
            return tool;
        }
        var message = failure instanceof SubprocessException
            ? toolName + " subprocess provider failed"
            : toolName + " could not start ripgrep";
        return new ToolException(ToolFailureCode.SEARCH_FAILED, message, failure);
    }

    private static ToolException invalidArgument(IllegalArgumentException failure) {
        return new ToolException(ToolFailureCode.INVALID_ARGUMENT, failure.getMessage(), failure);
    }

    private static final class ControlFailure extends RuntimeException {
        private final ToolFailureCode code;

        private ControlFailure(ToolFailureCode code, String message) {
            super(message);
            this.code = code;
        }
    }
}
