package com.sstlfsj.fibra.example.springboot;

import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.example.springboot.application.FibraSpringBootExampleApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** starter 的真实 engine 黑盒：上传 deployment、联合提交并调用插件服务。 */
@SpringBootTest(classes = FibraSpringBootExampleApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class FibraSpringBootExampleApplicationIT {
    private static final String PLUGIN_ID = "fibra-example-spring-boot-provider";
    private static final String PLUGIN_ZIP = PLUGIN_ID + "-1.0.0.zip";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FibraEngine engine;

    @DynamicPropertySource
    static void fibraProperties(DynamicPropertyRegistry registry) throws IOException {
        var work = Files.createTempDirectory("spring-boot-example-it");
        var plugins = Files.createDirectory(work.resolve("plugins"));
        var staging = Files.createDirectory(work.resolve("staging"));
        var config = Files.writeString(work.resolve("fibra.yaml"), "[]\n");
        registry.add("fibra.artifacts.installed-root", plugins::toString);
        registry.add("fibra.config.location", config::toString);
        registry.add("fibra.artifacts.watch.enabled", () -> "false");
        registry.add("fibra.config.watch.enabled", () -> "false");
        registry.add("fibra.example.staging-root", staging::toString);
    }

    @Test
    void uploadStagesThenDeploymentActivatesGreeting() throws Exception {
        var plugin = Path.of(System.getProperty("fibra.example.springboot.artifacts"))
            .resolve(PLUGIN_ZIP);
        assertTrue(Files.isRegularFile(plugin), "缺少插件 fixture ZIP: " + plugin);
        var deployment = deployment(plugin);

        var uploadBody = new LinkedMultiValueMap<String, Object>();
        uploadBody.add("file", new FileSystemResource(deployment));
        var uploadHeaders = new HttpHeaders();
        uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        var upload = rest.postForEntity("/deployments/upload",
            new HttpEntity<>(uploadBody, uploadHeaders), Map.class);
        assertEquals(HttpStatus.OK, upload.getStatusCode());
        assertEquals(deployment.getFileName().toString(), upload.getBody().get("staged"));

        assertEquals(HttpStatus.NOT_FOUND,
            rest.getForEntity("/greet?name=Ada", String.class).getStatusCode());

        var applyHeaders = new HttpHeaders();
        applyHeaders.setContentType(MediaType.APPLICATION_JSON);
        var apply = rest.postForEntity("/deployments/apply",
            new HttpEntity<>(Map.of("package", deployment.getFileName().toString()),
                applyHeaders), Map.class);
        assertEquals(HttpStatus.OK, apply.getStatusCode());
        assertEquals("spring-boot-example", apply.getBody().get("deploymentId"));
        assertTrue(((java.util.List<?>) apply.getBody().get("changedArtifactIds"))
            .contains(PLUGIN_ID));
        assertTrue(engine.isRunning());

        var greeting = rest.getForEntity("/greet?name=Ada", String.class);
        assertEquals(HttpStatus.OK, greeting.getStatusCode());
        assertNotNull(greeting.getBody());
        assertTrue(greeting.getBody().contains("Hello, Ada"), greeting.getBody());

        var status = rest.getForEntity("/deployments", Map.class);
        assertEquals(HttpStatus.OK, status.getStatusCode());
        assertTrue(((java.util.List<?>) status.getBody().get("staged"))
            .contains(deployment.getFileName().toString()));
    }

    private static Path deployment(Path plugin) throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("config/fibra.yaml", ("- id: " + PLUGIN_ID + "\n"
            + "  name: " + PLUGIN_ID + "\n").getBytes(StandardCharsets.UTF_8));
        entries.put("deployment.properties", ("deployment.id=spring-boot-example\n"
            + "deployment.version=1.0.0\n"
            + "config.path=config/fibra.yaml\n"
            + "plugin.0=plugins/" + PLUGIN_ZIP + "\n")
            .getBytes(StandardCharsets.ISO_8859_1));
        entries.put("plugins/" + PLUGIN_ZIP, Files.readAllBytes(plugin));
        var checksums = new StringBuilder();
        entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
            checksums.append(sha(entry.getValue())).append("  ")
                .append(entry.getKey()).append('\n'));
        entries.put("checksums.sha256", checksums.toString()
            .getBytes(StandardCharsets.UTF_8));
        var target = Files.createTempFile("spring-boot-example-deployment-", ".zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(target))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return target;
    }

    private static String sha(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
