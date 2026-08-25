package com.sstlfsj.fibra.example.springboot.application;

import com.sstlfsj.fibra.engine.FibraDeploymentResult;
import com.sstlfsj.fibra.engine.FibraEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 演示 deployment 上传、联合提交和 engine 状态查询。 */
@RestController
public class DeploymentAdminController {
    private final FibraEngine engine;
    private final Path stagingRoot;

    public DeploymentAdminController(FibraEngine engine,
        @Value("${fibra.example.staging-root}") Path stagingRoot) {
        this.engine = engine;
        this.stagingRoot = stagingRoot.toAbsolutePath().normalize();
    }

    @PostMapping("/deployments/upload")
    public Map<String, String> upload(@RequestParam("file") MultipartFile file)
        throws IOException {
        var name = safeName(file.getOriginalFilename());
        Files.createDirectories(stagingRoot);
        var target = stagingRoot.resolve(name);
        try (var input = file.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return Map.of("staged", name);
    }

    @PostMapping("/deployments/apply")
    public FibraDeploymentResult apply(@RequestBody Map<String, String> request) {
        var name = safeName(request.get("package"));
        var deployment = stagingRoot.resolve(name);
        if (!Files.isRegularFile(deployment)) {
            throw new IllegalArgumentException("暂存 deployment 不存在: " + name);
        }
        return engine.applyDeployment(deployment);
    }

    @GetMapping("/deployments")
    public Map<String, Object> status() {
        return Map.of("engine", engine.status(), "staged", staged());
    }

    private List<String> staged() {
        if (!Files.isDirectory(stagingRoot)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(stagingRoot)) {
            return entries.filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot list staging root " + stagingRoot,
                exception);
        }
    }

    private static String safeName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        var name = Path.of(rawName).getFileName().toString();
        if (!name.equals(rawName) || name.contains("..") || !name.endsWith(".zip")) {
            throw new IllegalArgumentException("非法 deployment 文件名: " + rawName);
        }
        return name;
    }
}
