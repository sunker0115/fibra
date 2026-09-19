package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.RuntimeId;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable provider registry; runtime identity is independent of placement. */
final class RuntimeProviderRegistry {
    private final Map<RuntimeId, RuntimeProvider> providers;

    private RuntimeProviderRegistry(Collection<? extends RuntimeProvider> values) {
        Objects.requireNonNull(values, "providers");
        var index = new LinkedHashMap<RuntimeId, RuntimeProvider>();
        values.stream().map(value -> Objects.requireNonNull(value, "provider"))
            .sorted(Comparator.comparing(value -> value.id().value()))
            .forEach(provider -> {
                var id = Objects.requireNonNull(provider.id(), "provider.id");
                if (index.putIfAbsent(id, provider) != null) {
                    throw new IllegalArgumentException(
                        "duplicate runtime provider " + id);
                }
            });
        providers = Collections.unmodifiableMap(index);
    }

    static RuntimeProviderRegistry of(
        Collection<? extends RuntimeProvider> providers) {
        return new RuntimeProviderRegistry(providers);
    }

    Map<RuntimeId, RuntimeProvider> providers() {
        return providers;
    }

    Map<RuntimeId, RuntimeDriver> createDrivers(
        RuntimeHostServices services) {
        Objects.requireNonNull(services, "services");
        var drivers = new LinkedHashMap<RuntimeId, RuntimeDriver>();
        try {
            for (var entry : providers.entrySet()) {
                var driver = Objects.requireNonNull(entry.getValue().create(services), "runtime driver");
                // 构造后即登记所有权，identity 校验失败也必须等待其关闭。
                drivers.put(entry.getKey(), driver);
                if (!entry.getKey().equals(driver.id())) {
                    throw new IllegalArgumentException("runtime driver identity mismatch for " + entry.getKey());
                }
            }
        } catch (RuntimeException | Error failure) {
            for (var driver : new java.util.ArrayList<>(drivers.values()).reversed()) {
                try { driver.closeAsync().block(); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
        return Collections.unmodifiableMap(drivers);
    }
}
