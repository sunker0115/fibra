package ${package};

public record PluginConfig(String message) {
    public PluginConfig {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
