package com.sstlfsj.fibra.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

@ConfigurationProperties("fibra")
public record FibraProperties(@DefaultValue(".fibra") Path storageRoot) {
}
