package com.sstlfsj.fibra.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("fibra.source")
public record FibraSourceProperties(
    @DefaultValue("0s") Duration refreshInterval) {

    public FibraSourceProperties {
        Objects.requireNonNull(refreshInterval, "refreshInterval");
        if (refreshInterval.isNegative()) {
            throw new IllegalArgumentException("refreshInterval must not be negative");
        }
    }
}
