package com.sstlfsj.fibra.plugins.fs.tool;

import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.plugins.fs.FileSystem;
import com.sstlfsj.fibra.plugins.fs.FsEdit;
import com.sstlfsj.fibra.plugins.fs.FsEditIntent;
import com.sstlfsj.fibra.plugins.fs.FsErrorCode;
import com.sstlfsj.fibra.plugins.fs.FsException;
import com.sstlfsj.fibra.plugins.fs.FsVersion;
import com.sstlfsj.fibra.plugins.fs.FsWriteIntent;
import com.sstlfsj.fibra.plugins.tool.ToolException;
import com.sstlfsj.fibra.plugins.tool.ToolFailureCode;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
import com.sstlfsj.fibra.plugins.tool.ToolResult;
import com.sstlfsj.fibra.value.LiteralValue;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Argument decoding and failure projection for the formal file-tool contributions. */
public final class FileToolHandlers {
    private static final Set<String> READ_ARGUMENTS = Set.of("path", "cwd", "offset", "limit");
    private static final Set<String> WRITE_ARGUMENTS = Set.of("path", "cwd", "content",
        "createIfAbsent", "version");
    private static final Set<String> EDIT_ARGUMENTS = Set.of("path", "cwd", "oldText", "newText",
        "replaceAll", "version");

    private FileToolHandlers() {
    }

    public static Mono<ToolResult> read(FileSystem fileSystem, InvocationContext invocation,
                                        FileToolConfig config, ToolRequest request) {
        Objects.requireNonNull(config, "config");
        return withRequestContext(invocation, request, context -> {
            var arguments = request.arguments().values();
            rejectUnknown(arguments, READ_ARGUMENTS);
            var offset = positive(arguments, "offset", 1);
            var limit = positive(arguments, "limit", config.readLimit());
            if (limit > config.readLimit()) {
                throw invalid("limit must be less than or equal to " + config.readLimit());
            }
            return fileSystem.resolve(context, nonBlankText(arguments, "path"),
                    optionalText(arguments, "cwd"))
                .flatMap(target -> fileSystem.stat(context, target)
                    .flatMap(info -> fileSystem.readText(context, target, config.readSourceMaxBytes())
                        .map(content -> readResult(target.displayPath(), info.version().value(), content,
                            Math.toIntExact(offset), Math.toIntExact(limit), config)))) ;
        });
    }

    public static Mono<ToolResult> write(FileSystem fileSystem, InvocationContext invocation,
                                         ToolRequest request) {
        return withRequestContext(invocation, request, context -> {
            var arguments = request.arguments().values();
            rejectUnknown(arguments, WRITE_ARGUMENTS);
            var createIfAbsent = bool(arguments, "createIfAbsent", false);
            var observedVersion = optionalText(arguments, "version");
            if (createIfAbsent && observedVersion != null) {
                throw invalid("createIfAbsent and version are mutually exclusive");
            }
            var intent = writeIntent(createIfAbsent, observedVersion);
            return fileSystem.resolve(context, nonBlankText(arguments, "path"), optionalText(arguments, "cwd"))
                .flatMap(target -> fileSystem.writeText(context, target, requiredText(arguments, "content"), intent)
                    .map(result -> {
                        var data = new LinkedHashMap<String, Object>();
                        data.put("path", target.displayPath());
                        data.put("operation", result.operation().name().toLowerCase(java.util.Locale.ROOT));
                        data.put("version", result.version().value());
                        data.put("before", result.before());
                        data.put("after", result.after());
                        var verb = result.operation().name().equals("CREATE") ? "Created" : "Updated";
                        var text = "<path>" + target.displayPath() + "</path>\n<type>file</type>\n"
                            + "<content>\n" + verb + " file\n</content>";
                        return ToolResult.textAndStructured(text, LiteralValue.of(data));
                    }));
        });
    }

    public static Mono<ToolResult> edit(FileSystem fileSystem, InvocationContext invocation,
                                        ToolRequest request) {
        return withRequestContext(invocation, request, context -> {
            var arguments = request.arguments().values();
            rejectUnknown(arguments, EDIT_ARGUMENTS);
            var replaceAll = bool(arguments, "replaceAll", false);
            var edit = new FsEdit(nonEmptyText(arguments, "oldText"), requiredText(arguments, "newText"),
                replaceAll);
            return fileSystem.resolve(context, nonBlankText(arguments, "path"), optionalText(arguments, "cwd"))
                .flatMap(target -> fileSystem.editText(context, target, edit, editIntent(arguments))
                    .map(result -> {
                        var data = new LinkedHashMap<String, Object>();
                        data.put("path", target.displayPath());
                        data.put("operation", "edit");
                        data.put("version", result.version().value());
                        data.put("before", result.before());
                        data.put("after", result.after());
                        var text = replaceAll
                            ? "The file " + target.displayPath()
                                + " has been updated. All occurrences were successfully replaced."
                            : "The file " + target.displayPath() + " has been updated successfully.";
                        return ToolResult.textAndStructured(text, LiteralValue.of(data));
                    }));
        });
    }

    private static ToolResult readResult(String path, String version, String content, int offset,
                                         int limit, FileToolConfig config) {
        var rawLines = lines(content);
        if (offset > rawLines.size() && !(rawLines.isEmpty() && offset == 1)) {
            throw new ToolException(ToolFailureCode.NOT_FOUND,
                "offset " + offset + " is out of range for \"" + path + "\" ("
                    + rawLines.size() + " lines)");
        }
        var lines = new ArrayList<Map<String, Object>>();
        long outputBytes = 0;
        var truncatedByBytes = false;
        for (var index = offset - 1; index < rawLines.size() && lines.size() < limit; index++) {
            var text = truncate(rawLines.get(index), config.readMaxLineLength());
            var bytes = text.getBytes(StandardCharsets.UTF_8).length + (lines.isEmpty() ? 0 : 1);
            if (outputBytes + bytes > config.readMaxBytes()) {
                truncatedByBytes = true;
                break;
            }
            outputBytes += bytes;
            lines.add(Map.of("number", index + 1, "text", text));
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("path", path);
        data.put("version", version);
        data.put("offset", offset);
        data.put("lines", lines);
        data.put("totalLines", rawLines.size());
        return ToolResult.textAndStructured(formatRead(path, offset, lines, rawLines.size(), truncatedByBytes),
            LiteralValue.of(data));
    }

    private static java.util.List<String> lines(String content) {
        if (content.isEmpty()) return java.util.List.of();
        var result = new ArrayList<String>();
        var start = 0;
        for (var index = 0; index < content.length(); index++) {
            if (content.charAt(index) == '\n') {
                var line = content.substring(start, index);
                result.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
                start = index + 1;
            }
        }
        if (start < content.length()) result.add(content.substring(start));
        return result;
    }

    private static String truncate(String line, int maxLength) {
        return line.length() > maxLength
            ? line.substring(0, maxLength) + "... (line truncated to " + maxLength + " chars)"
            : line;
    }

    private static String formatRead(String path, int offset, java.util.List<Map<String, Object>> lines,
                                     int totalLines, boolean truncatedByBytes) {
        var body = new StringBuilder();
        for (var line : lines) {
            if (!body.isEmpty()) body.append('\n');
            body.append(line.get("number")).append(": ").append(line.get("text"));
        }
        var endLine = lines.isEmpty() ? Math.max(0, offset - 1)
            : (Integer) lines.get(lines.size() - 1).get("number");
        if (!body.isEmpty()) body.append("\n\n");
        if (truncatedByBytes) {
            body.append("(Output capped. Showing lines ").append(offset).append('-').append(endLine)
                .append(". Use offset=").append(endLine + 1).append(" to continue.)");
        } else if (endLine < totalLines) {
            body.append("(Showing lines ").append(offset).append('-').append(endLine).append(" of ")
                .append(totalLines).append(". Use offset=").append(endLine + 1).append(" to continue.)");
        } else {
            body.append("(End of file - total ").append(totalLines).append(" lines)");
        }
        return "<path>" + path + "</path>\n<type>file</type>\n<content>\n"
            + body + "\n</content>";
    }

    private static Mono<ToolResult> withRequestContext(InvocationContext invocation, ToolRequest request,
                                                        java.util.function.Function<InvocationContext,
                                                            Mono<ToolResult>> operation) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(request, "request");
        try {
            return operation.apply(invocation.withCancellation(request.cancellation()))
                .onErrorMap(FileToolHandlers::toolFailure);
        } catch (RuntimeException failure) {
            return Mono.error(toolFailure(failure));
        }
    }

    private static FsWriteIntent writeIntent(boolean createIfAbsent, String version) {
        if (createIfAbsent) return new FsWriteIntent.CreateIfAbsent();
        return version == null ? new FsWriteIntent.Unconditional()
            : new FsWriteIntent.ReplaceIfVersion(new FsVersion(version));
    }

    private static FsEditIntent editIntent(Map<String, LiteralValue> values) {
        var version = optionalText(values, "version");
        return version == null ? new FsEditIntent.Unconditional()
            : new FsEditIntent.ReplaceIfVersion(new FsVersion(version));
    }

    private static void rejectUnknown(Map<String, LiteralValue> values, Set<String> allowed) {
        var unknown = values.keySet().stream().filter(name -> !allowed.contains(name)).findFirst();
        if (unknown.isPresent()) throw invalid("unknown argument: " + unknown.get());
    }

    private static String nonBlankText(Map<String, LiteralValue> values, String name) {
        var result = requiredText(values, name);
        if (!result.isBlank()) return result;
        throw invalid(name + " must be non-blank text");
    }

    private static String nonEmptyText(Map<String, LiteralValue> values, String name) {
        var result = requiredText(values, name);
        if (!result.isEmpty()) return result;
        throw invalid(name + " must be non-empty text");
    }

    private static String requiredText(Map<String, LiteralValue> values, String name) {
        var value = values.get(name);
        if (value instanceof LiteralValue.StringValue text) return text.value();
        throw invalid(name + " must be text");
    }

    private static String optionalText(Map<String, LiteralValue> values, String name) {
        var value = values.get(name);
        if (value == null || value instanceof LiteralValue.NullValue) return null;
        if (value instanceof LiteralValue.StringValue text && !text.value().isBlank()) return text.value();
        throw invalid(name + " must be non-blank text");
    }

    private static long positive(Map<String, LiteralValue> values, String name, long defaultValue) {
        var value = values.get(name);
        if (value == null || value instanceof LiteralValue.NullValue) return defaultValue;
        if (value instanceof LiteralValue.NumberValue number) {
            try {
                var result = number.value().longValueExact();
                if (result > 0) return result;
            } catch (ArithmeticException ignored) {
                // Fall through to the stable invalid-argument error.
            }
        }
        throw invalid(name + " must be a positive integer");
    }

    private static boolean bool(Map<String, LiteralValue> values, String name, boolean defaultValue) {
        var value = values.get(name);
        if (value == null || value instanceof LiteralValue.NullValue) return defaultValue;
        if (value instanceof LiteralValue.BooleanValue bool) return bool.value();
        throw invalid(name + " must be boolean");
    }

    private static ToolException toolFailure(Throwable failure) {
        if (failure instanceof ToolException exception) return exception;
        if (failure instanceof FsException exception) return new ToolException(map(exception.code()),
            exception.getMessage(), exception);
        if (failure instanceof ArithmeticException || failure instanceof IllegalArgumentException) {
            return invalid(failure.getMessage() == null ? "invalid argument" : failure.getMessage());
        }
        return new ToolException(ToolFailureCode.IO_ERROR, "file tool failed", failure);
    }

    private static ToolFailureCode map(FsErrorCode code) {
        return switch (code) {
            case NOT_FOUND -> ToolFailureCode.NOT_FOUND;
            case NOT_DIRECTORY -> ToolFailureCode.NOT_DIRECTORY;
            case NOT_TEXT, NOT_REGULAR_FILE -> ToolFailureCode.NOT_TEXT;
            case TOO_LARGE -> ToolFailureCode.TOO_LARGE;
            case PERMISSION_DENIED -> ToolFailureCode.PERMISSION_DENIED;
            case STALE_VERSION -> ToolFailureCode.STALE_VERSION;
            case NOT_OBSERVED -> ToolFailureCode.NOT_OBSERVED;
            case AMBIGUOUS_EDIT -> ToolFailureCode.AMBIGUOUS_EDIT;
            case EDIT_NOT_FOUND -> ToolFailureCode.EDIT_NOT_FOUND;
            case ABORTED -> ToolFailureCode.ABORTED;
            case IO_ERROR -> ToolFailureCode.IO_ERROR;
        };
    }

    private static ToolException invalid(String message) {
        return new ToolException(ToolFailureCode.INVALID_ARGUMENT, message);
    }
}
