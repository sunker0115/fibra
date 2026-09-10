package com.sstlfsj.fibra.example;

import com.sstlfsj.fibra.example.sanitizer.SanitizeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class JavaHost {
    private static final Logger log = LoggerFactory.getLogger(JavaHost.class);

    private JavaHost() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                "usage: <content-sanitizer-plugin-directory> <text>");
        }
        var configuredNode = System.getenv("FIBRA_NODE");
        var node = configuredNode == null || configuredNode.isBlank()
            ? Path.of("node") : Path.of(configuredNode);
        var storage = Files.createTempDirectory("fibra-sanitizer-example-");
        try (var scenario = ContentSanitizerScenario.open(
            Path.of(args[0]), node, storage)) {
            log.info("Loaded contribution: {}", scenario.descriptor());
            var result = scenario.sanitize(new SanitizeRequest(args[1]));
            log.info("Sanitized text: {}", result.text());
            log.info("Redactions: {} (total={})", result.redactions(), result.total());
            scenario.remove();
            log.info("Audit operations: {}", scenario.registry().history().stream()
                .map(entry -> entry.operation()).toList());
        }
    }
}
