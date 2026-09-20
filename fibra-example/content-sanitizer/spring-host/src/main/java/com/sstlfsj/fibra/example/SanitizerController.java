package com.sstlfsj.fibra.example;

import com.sstlfsj.fibra.example.sanitizer.SanitizeRequest;
import com.sstlfsj.fibra.example.sanitizer.SanitizeResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
final class SanitizerController {
    private final SanitizerPluginManager plugins;

    SanitizerController(SanitizerPluginManager plugins) {
        this.plugins = plugins;
    }

    @PostMapping("/sanitize")
    SanitizeResult sanitize(@RequestBody SanitizeRequest request) {
        return plugins.sanitize(request);
    }

    @GetMapping("/plugins")
    List<PluginView> plugins() {
        return plugins.registry().list().stream().map(state -> new PluginView(
            state.entryId(), state.desired() != null && state.desired().enabled(),
            state.observed() == null ? "ABSENT" : state.observed().aggregateState().name()
        )).toList();
    }

    record PluginView(String instanceId, boolean enabled, String observedState) {
    }
}
