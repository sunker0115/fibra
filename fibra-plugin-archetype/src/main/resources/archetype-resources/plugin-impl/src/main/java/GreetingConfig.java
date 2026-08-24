package ${package}.plugin;

public record GreetingConfig(String prefix) {
    public GreetingConfig {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
    }
}
