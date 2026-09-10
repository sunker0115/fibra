package com.sstlfsj.fibra.example;

import com.sstlfsj.fibra.registry.PluginRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = FibraSpringHost.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FibraSpringHostIT {
    private static final Path STORAGE_ROOT = temporaryStorage();

    @LocalServerPort
    private int port;

    @Autowired
    private PluginRegistry registry;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("fibra.storage-root", STORAGE_ROOT::toString);
        properties.add("example.sanitizer.plugin-directory",
            () -> System.getProperty("fibra.example.plugin"));
        properties.add("example.sanitizer.node-executable",
            () -> System.getProperty("fibra.test.node", "node"));
    }

    @Test
    void exposesTheNodePluginAsARegularSpringHttpCapability() throws Exception {
        var response = post("/api/sanitize", """
            {"text":"Contact alice@example.com with Bearer abcdefghijklmnop or sk_1234567890abcdef"}
            """);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains(
            "Contact [REDACTED] with [REDACTED] or [REDACTED]"));
        assertTrue(response.body().contains("\"email\":1"));
        assertTrue(response.body().contains("\"bearer-token\":1"));
        assertTrue(response.body().contains("\"api-key\":1"));
        assertTrue(response.body().contains("\"total\":3"));
        assertTrue(registry.get(SanitizerPluginManager.INSTANCE_ID).isPresent());

        var plugins = get("/api/plugins");
        assertEquals(200, plugins.statusCode());
        assertTrue(plugins.body().contains("\"instanceId\":\"default-sanitizer\""));
        assertTrue(plugins.body().contains("\"observedState\":\"ACTIVE\""));
    }

    private HttpResponse<String> post(String path, String body)
        throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> get(String path)
        throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .GET().build();
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("fibra-spring-example-");
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
