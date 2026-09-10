package com.sstlfsj.fibra.example;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

@ConfigurationProperties("example.sanitizer")
record SanitizerExampleProperties(Path pluginDirectory,
                                  @DefaultValue("node") Path nodeExecutable) {
    SanitizerExampleProperties {
        if (pluginDirectory == null) {
            throw new IllegalArgumentException(
                "example.sanitizer.plugin-directory is required");
        }
    }
}
