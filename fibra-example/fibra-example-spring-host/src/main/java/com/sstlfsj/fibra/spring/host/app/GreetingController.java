package com.sstlfsj.fibra.spring.host.app;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.spring.host.Greeting;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通过类型化 {@link Greeting#KEY} 解析当前 ACTIVE 插件 provider 并调用。
 * 无活跃 provider（未 apply/已卸载）时返回 404，而非 500。
 */
@RestController
public class GreetingController {
    private final Context root;

    public GreetingController(Context fibraRootContext) {
        this.root = fibraRootContext;
    }

    @GetMapping("/greet")
    public ResponseEntity<String> greet(@RequestParam("name") String name) {
        try {
            String result = root.service(Greeting.KEY).invoke((invocation, greeting) -> greeting.greet(name));
            return ResponseEntity.ok(result);
        } catch (RuntimeException inactive) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("greeting service inactive");
        }
    }
}
