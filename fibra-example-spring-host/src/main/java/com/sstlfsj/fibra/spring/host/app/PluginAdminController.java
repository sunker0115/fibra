package com.sstlfsj.fibra.spring.host.app;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.loader.pf4j.PluginInstanceSpec;
import com.sstlfsj.fibra.spring.FibraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 演示"上传（仅暂存）→ 请求驱动热装（apply）→ mount → 热卸"的宿主管理端点。
 *
 * <p><strong>安全警告：</strong>{@code /plugins/apply} 等价于任意代码执行；本示例为演示机制，
 * 生产环境必须在 apply 前完成鉴权、来源校验与签名验证，禁止裸开上传/apply 口。
 */
@RestController
public class PluginAdminController {
    private static final Logger log = LoggerFactory.getLogger(PluginAdminController.class);

    private final FibraPluginLoader loader;
    private final Context root;
    private final Path stagingRoot;

    public PluginAdminController(FibraPluginLoader loader, Context fibraRootContext,
                                 FibraProperties props) {
        this.loader = loader;
        this.root = fibraRootContext;
        this.stagingRoot = props.getStagingRoot();
    }

    /** 只把上传的 ZIP 存到 staging-root，不 apply、不生效。 */
    @PostMapping("/plugins/upload")
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String name = safeName(file.getOriginalFilename());
        Files.createDirectories(stagingRoot);
        Path target = stagingRoot.resolve(name);
        try (var input = file.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return Map.of("staged", name);
    }

    /** 对指定暂存候选执行热装并 mount，返回装入的 pluginId 与 mount 的 entryId。 */
    @PostMapping("/plugins/apply")
    public Map<String, Object> apply(@RequestBody List<String> candidates) {
        List<Path> paths = new ArrayList<>();
        for (String candidate : candidates) {
            Path path = stagingRoot.resolve(safeName(candidate));
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("暂存候选不存在: " + candidate);
            }
            paths.add(path);
        }
        List<String> applied = loader.applyArtifacts(paths);
        List<String> mounted = new ArrayList<>();
        for (String pluginId : applied) {
            if (loader.fibra(pluginId).isEmpty()) {
                loader.mount(PluginInstanceSpec.builder(pluginId, pluginId)
                    .parentContext(root)
                    .build());
            }
            mounted.add(pluginId);
        }
        return Map.of("applied", applied, "mounted", mounted);
    }

    /** 列出已装制品、已 mount 入口与暂存文件。 */
    @GetMapping("/plugins")
    public Map<String, Object> list() {
        return Map.of(
            "artifacts", loader.artifactIds(),
            "entries", loader.entryIds(),
            "staged", staged());
    }

    /** 热卸指定制品（连同其入口）。 */
    @DeleteMapping("/plugins/{pluginId}")
    public Map<String, Object> unload(@PathVariable("pluginId") String pluginId) {
        boolean removed = loader.unloadArtifact(pluginId);
        return Map.of("unloaded", removed);
    }

    private List<String> staged() {
        if (!Files.isDirectory(stagingRoot)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(stagingRoot)) {
            return entries.filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot list staging root " + stagingRoot, exception);
        }
    }

    private static String safeName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String name = Path.of(rawName).getFileName().toString();
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException("非法文件名: " + rawName);
        }
        return name;
    }
}
