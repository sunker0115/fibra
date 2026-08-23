package com.sstlfsj.fibra.spring.host;

import com.sstlfsj.fibra.spring.host.app.ExampleSpringHostApplication;
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
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fibra-spring-boot-starter 的端到端黑盒：上传（仅暂存）→ 请求驱动热装 → mount →
 * URL 调用插件贡献的 Greeting → 热卸。
 */
@SpringBootTest(classes = ExampleSpringHostApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ExampleSpringHostIT {
    private static final String PLUGIN_ID = "fibra-example-spring-host-provider";
    private static final String PLUGIN_ZIP = PLUGIN_ID + "-1.0.0.zip";

    @Autowired
    private TestRestTemplate rest;

    @DynamicPropertySource
    static void fibraProperties(DynamicPropertyRegistry registry) throws IOException {
        Path work = Files.createTempDirectory("spring-host-it");
        Path plugins = Files.createDirectory(work.resolve("plugins"));
        Path staging = Files.createDirectory(work.resolve("staging"));
        Path config = work.resolve("fibra.yaml");
        Files.writeString(config, "[]\n");
        registry.add("fibra.plugins-root", plugins::toString);
        registry.add("fibra.staging-root", staging::toString);
        registry.add("fibra.config-location", config::toString);
        registry.add("fibra.watcher.enabled", () -> "false");
    }

    @Test
    void uploadStagesThenApplyMountsGreetingAndUnloadRemovesIt() throws Exception {
        Path zip = Path.of(System.getProperty("fibra.springhost.artifacts")).resolve(PLUGIN_ZIP);
        assertTrue(Files.isRegularFile(zip), "缺少插件 fixture ZIP: " + zip);

        // 1. 上传：仅暂存，不生效。
        var uploadBody = new LinkedMultiValueMap<String, Object>();
        uploadBody.add("file", new FileSystemResource(zip));
        var uploadHeaders = new HttpHeaders();
        uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        var upload = rest.postForEntity("/plugins/upload",
            new HttpEntity<>(uploadBody, uploadHeaders), Map.class);
        assertEquals(HttpStatus.OK, upload.getStatusCode());
        assertEquals(PLUGIN_ZIP, upload.getBody().get("staged"));

        // 2. 未 apply：greet 无活跃 provider，不生效。
        var beforeApply = rest.getForEntity("/greet?name=Ada", String.class);
        assertEquals(HttpStatus.NOT_FOUND, beforeApply.getStatusCode(),
            "未 apply 时 greet 不应有结果");

        // 3. apply + mount。
        var applyHeaders = new HttpHeaders();
        applyHeaders.setContentType(MediaType.APPLICATION_JSON);
        var apply = rest.postForEntity("/plugins/apply",
            new HttpEntity<>(List.of(PLUGIN_ZIP), applyHeaders), Map.class);
        assertEquals(HttpStatus.OK, apply.getStatusCode());
        assertTrue(((List<?>) apply.getBody().get("applied")).contains(PLUGIN_ID),
            "apply 应装入 " + PLUGIN_ID);
        assertTrue(((List<?>) apply.getBody().get("mounted")).contains(PLUGIN_ID),
            "apply 应 mount " + PLUGIN_ID);

        // 4. URL 调插件贡献的 Greeting。
        var afterApply = rest.getForEntity("/greet?name=Ada", String.class);
        assertEquals(HttpStatus.OK, afterApply.getStatusCode());
        assertNotNull(afterApply.getBody());
        assertTrue(afterApply.getBody().contains("Hello, Ada"),
            "greet 应返回插件问候，实际=" + afterApply.getBody());

        // 5. 列表含入口。
        var listed = rest.getForEntity("/plugins", Map.class);
        assertTrue(((List<?>) listed.getBody().get("entries")).contains(PLUGIN_ID));

        // 6. 热卸。
        var unload = rest.exchange("/plugins/" + PLUGIN_ID,
            org.springframework.http.HttpMethod.DELETE, null, Map.class);
        assertEquals(HttpStatus.OK, unload.getStatusCode());
        assertEquals(Boolean.TRUE, unload.getBody().get("unloaded"));

        // 7. 卸载后入口消失，greet 再次失效。
        var afterUnload = rest.getForEntity("/plugins", Map.class);
        assertFalse(((List<?>) afterUnload.getBody().get("entries")).contains(PLUGIN_ID),
            "热卸后不应再有该入口");
        assertEquals(HttpStatus.NOT_FOUND,
            rest.getForEntity("/greet?name=Ada", String.class).getStatusCode());
    }
}
