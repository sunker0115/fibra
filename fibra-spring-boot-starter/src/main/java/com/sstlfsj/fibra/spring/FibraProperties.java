package com.sstlfsj.fibra.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("fibra")
public class FibraProperties {
    private Path pluginsRoot;
    private Path stagingRoot;
    private Path configLocation;
    private List<String> startupRequiredPlugins = new ArrayList<>();
    private Duration shutdownTimeout = Duration.ofSeconds(30);
    private final Watcher watcher = new Watcher();

    public Path getPluginsRoot() { return pluginsRoot; }
    public void setPluginsRoot(Path v) { this.pluginsRoot = v; }
    public Path getStagingRoot() { return stagingRoot; }
    public void setStagingRoot(Path v) { this.stagingRoot = v; }
    public Path getConfigLocation() { return configLocation; }
    public void setConfigLocation(Path v) { this.configLocation = v; }
    public List<String> getStartupRequiredPlugins() { return startupRequiredPlugins; }
    public void setStartupRequiredPlugins(List<String> v) { this.startupRequiredPlugins = v; }
    public Duration getShutdownTimeout() { return shutdownTimeout; }
    public void setShutdownTimeout(Duration v) { this.shutdownTimeout = v; }
    public Watcher getWatcher() { return watcher; }

    public static class Watcher {
        private boolean enabled = false;
        private Duration debounce = Duration.ofSeconds(1);
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public Duration getDebounce() { return debounce; }
        public void setDebounce(Duration v) { this.debounce = v; }
    }
}
