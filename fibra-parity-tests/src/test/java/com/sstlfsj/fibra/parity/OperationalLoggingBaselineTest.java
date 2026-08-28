package com.sstlfsj.fibra.parity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationalLoggingBaselineTest {
    private static final Pattern EVENT = Pattern.compile(
        "\\.log\\(\"event=(fibra\\.[a-z0-9_]+(?:\\.[a-z0-9_]+)+)(?:[ \\\"]|\"\\))");

    @Test
    void productionDiagnosticsUseOneStructuredSlf4jConvention() throws Exception {
        for (var source : productionSources()) {
            var content = Files.readString(source);
            assertFalse(content.contains("System.out") || content.contains("System.err"),
                source + " 不得直接写标准输出或错误输出");
            assertFalse(content.contains("ch.qos.logback")
                    || content.contains("org.apache.logging.log4j"),
                source + " 不得依赖具体日志后端");

            if (!content.contains("org.slf4j.Logger;")
                || source.getFileName().toString().equals("DefaultLoggerService.java")) {
                continue;
            }
            assertTrue(content.contains("private static final Logger LOGGER"),
                source + " 内部日志字段必须统一命名为 LOGGER");
            assertFalse(content.matches("(?s).*LOGGER\\.(trace|debug|info|warn|error)\\(.*"),
                source + " 运行诊断必须使用 SLF4J fluent API");
            assertFalse(content.contains(".addKeyValue("),
                source + " 运行诊断字段必须写入消息正文，不能依赖日志后端是否渲染键值对");

            var fluentCalls = occurrences(content, "LOGGER.at");
            var events = new ArrayList<String>();
            var matcher = EVENT.matcher(content);
            while (matcher.find()) {
                events.add(matcher.group(1));
            }
            assertEquals(fluentCalls, events.size(),
                source + " 每条运行诊断必须携带一个稳定 event");
        }
    }

    private static int occurrences(String content, String needle) {
        var count = 0;
        var offset = 0;
        while ((offset = content.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static List<Path> productionSources() throws Exception {
        var root = repositoryRoot();
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> path.toString().contains("/src/main/java/"))
                .filter(path -> root.relativize(path).getName(0).toString()
                    .startsWith("fibra-"))
                .filter(path -> !path.toString().contains("/target/"))
                .sorted()
                .toList();
        }
    }

    private static Path repositoryRoot() {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        return current.getFileName().toString().equals("fibra-parity-tests")
            ? current.getParent() : current;
    }
}
