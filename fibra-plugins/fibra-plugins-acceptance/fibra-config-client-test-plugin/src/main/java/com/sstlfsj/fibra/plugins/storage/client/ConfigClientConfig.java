package com.sstlfsj.fibra.plugins.storage.client;

public record ConfigClientConfig(String eventLog) {
    public ConfigClientConfig() {
        this(null);
    }
}
