package com.sstlfsj.fibra.plugins.fs.tool;

import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.plugins.fs.FileSystem;
import com.sstlfsj.fibra.plugins.fs.FsErrorCode;
import com.sstlfsj.fibra.plugins.fs.FsException;
import com.sstlfsj.fibra.plugins.fs.FsTarget;
import com.sstlfsj.fibra.plugins.fs.FsVersion;
import com.sstlfsj.fibra.plugins.fs.FsWriteIntent;
import com.sstlfsj.fibra.plugins.fs.FsWriteOperation;
import com.sstlfsj.fibra.plugins.fs.FsWriteResult;
import com.sstlfsj.fibra.plugins.tool.ToolException;
import com.sstlfsj.fibra.plugins.tool.ToolFailureCode;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileToolHandlersTest {
    private final FibraRuntime runtime = FibraRuntime.create();

    @AfterEach
    void closeRuntime() {
        runtime.close();
    }

    @Test
    void writesThroughFileSystemWithRequestCancellationAndMapsFsFailure() {
        var observedCancellation = new AtomicReference<com.sstlfsj.fibra.CancellationToken>();
        FileSystem fileSystem = new FileSystem() {
            @Override public Mono<FsTarget> resolve(InvocationContext context, String path, String cwd) {
                observedCancellation.set(context.cancellation());
                return Mono.just(new FsTarget("opaque", "note.txt"));
            }
            @Override public Mono<com.sstlfsj.fibra.plugins.fs.FsInfo> stat(InvocationContext context, FsTarget target) {
                return Mono.error(new UnsupportedOperationException());
            }
            @Override public Mono<String> readText(InvocationContext context, FsTarget target, long maxBytes) {
                return Mono.error(new UnsupportedOperationException());
            }
            @Override public Mono<FsWriteResult> writeText(InvocationContext context, FsTarget target, String content,
                                                            FsWriteIntent intent) {
                return Mono.just(new FsWriteResult(FsWriteOperation.CREATE, new FsVersion("v1"), null, content));
            }
            @Override public Mono<com.sstlfsj.fibra.plugins.fs.FsEditResult> editText(InvocationContext context,
                FsTarget target, com.sstlfsj.fibra.plugins.fs.FsEdit edit,
                com.sstlfsj.fibra.plugins.fs.FsEditIntent intent) {
                return Mono.error(new UnsupportedOperationException());
            }
        };
        var request = ToolRequest.of(Map.of("path", "note.txt", "content", "hello"));

        var result = FileToolHandlers.write(fileSystem, context(), request).block();

        assertEquals(request.cancellation(), observedCancellation.get());
        assertTrue(result.text().contains("Created file"));
        var data = (Map<?, ?>) result.data().toJava();
        assertEquals("note.txt", data.get("path"));
        assertEquals("create", data.get("operation"));
        assertEquals("v1", data.get("version"));
        assertEquals(null, data.get("before"));
        assertEquals("hello", data.get("after"));
        assertCode(ToolFailureCode.NOT_FOUND, () -> FileToolHandlers.read(new FailingFileSystem(), context(),
            new FileToolConfig(), ToolRequest.of(Map.of("path", "missing.txt"))).block());
    }

    @Test
    void readReturnsOneBasedBoundedStructuredLines() {
        FileSystem fileSystem = new TextFileSystem("first\nsecond\nthird\n", new FsVersion("v7"));
        var config = new FileToolConfig(2, 2_000, 50L * 1_024, 1_048_576L);

        var result = FileToolHandlers.read(fileSystem, context(), config,
            ToolRequest.of(Map.of("path", "note.txt", "offset", 2, "limit", 2))).block();

        assertEquals(Map.of(
            "path", "note.txt",
            "version", "v7",
            "offset", new java.math.BigDecimal("2"),
            "lines", java.util.List.of(
                Map.of("number", new java.math.BigDecimal("2"), "text", "second"),
                Map.of("number", new java.math.BigDecimal("3"), "text", "third")),
            "totalLines", new java.math.BigDecimal("3")), result.data().toJava());
        assertTrue(result.text().contains("2: second"));
        assertTrue(result.text().contains("End of file - total 3 lines"));
        assertCode(ToolFailureCode.INVALID_ARGUMENT, () -> FileToolHandlers.read(fileSystem,
            context(), config, ToolRequest.of(Map.of("path", "note.txt", "offset", 0))).block());
        assertCode(ToolFailureCode.INVALID_ARGUMENT, () -> FileToolHandlers.read(fileSystem,
            context(), config, ToolRequest.of(Map.of("path", "note.txt", "limit", 3))).block());
    }

    private InvocationContext context() {
        return InvocationContext.of(runtime.rootScope().context(), "fibra.tool");
    }

    private static void assertCode(ToolFailureCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ToolException.class, action).code());
    }

    private static final class FailingFileSystem implements FileSystem {
        @Override public Mono<FsTarget> resolve(InvocationContext context, String path, String cwd) {
            return Mono.error(new FsException(FsErrorCode.NOT_FOUND, "missing"));
        }
        @Override public Mono<com.sstlfsj.fibra.plugins.fs.FsInfo> stat(InvocationContext context, FsTarget target) {
            return Mono.error(new UnsupportedOperationException());
        }
        @Override public Mono<String> readText(InvocationContext context, FsTarget target, long maxBytes) {
            return Mono.error(new UnsupportedOperationException());
        }
        @Override public Mono<FsWriteResult> writeText(InvocationContext context, FsTarget target, String content,
                                                        FsWriteIntent intent) {
            return Mono.error(new UnsupportedOperationException());
        }
        @Override public Mono<com.sstlfsj.fibra.plugins.fs.FsEditResult> editText(InvocationContext context,
            FsTarget target, com.sstlfsj.fibra.plugins.fs.FsEdit edit,
            com.sstlfsj.fibra.plugins.fs.FsEditIntent intent) {
            return Mono.error(new UnsupportedOperationException());
        }
    }

    private static final class TextFileSystem implements FileSystem {
        private final String content;
        private final FsVersion version;

        private TextFileSystem(String content, FsVersion version) {
            this.content = content;
            this.version = version;
        }

        @Override public Mono<FsTarget> resolve(InvocationContext context, String path, String cwd) {
            return Mono.just(new FsTarget("opaque", path));
        }
        @Override public Mono<com.sstlfsj.fibra.plugins.fs.FsInfo> stat(InvocationContext context,
                                                                        FsTarget target) {
            return Mono.just(new com.sstlfsj.fibra.plugins.fs.FsInfo(
                com.sstlfsj.fibra.plugins.fs.FsFileType.FILE, version,
                (long) content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
        }
        @Override public Mono<String> readText(InvocationContext context, FsTarget target, long maxBytes) {
            return Mono.just(content);
        }
        @Override public Mono<FsWriteResult> writeText(InvocationContext context, FsTarget target,
                                                        String content, FsWriteIntent intent) {
            return Mono.error(new UnsupportedOperationException());
        }
        @Override public Mono<com.sstlfsj.fibra.plugins.fs.FsEditResult> editText(
            InvocationContext context, FsTarget target, com.sstlfsj.fibra.plugins.fs.FsEdit edit,
            com.sstlfsj.fibra.plugins.fs.FsEditIntent intent) {
            return Mono.error(new UnsupportedOperationException());
        }
    }
}
