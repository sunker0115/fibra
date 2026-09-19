package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.engine.RuntimeDriver;
import com.sstlfsj.fibra.engine.RuntimeHostServices;
import com.sstlfsj.fibra.engine.RuntimeProvider;

import java.util.List;
import java.util.Objects;

/** Java runtime 的唯一 provider；执行资源只由其 driver 的 generation 持有。 */
public final class JavaRuntimeProvider implements RuntimeProvider {
    public static final RuntimeId RUNTIME_ID = new RuntimeId("java");
    public static final String CONTRACT_IDENTITY = "fibra-runtime-java:task10-v1";
    private final List<JavaBuiltInPackage> builtIns;

    public JavaRuntimeProvider(List<JavaBuiltInPackage> builtIns) {
        this.builtIns = List.copyOf(Objects.requireNonNull(builtIns, "builtIns"));
        if (this.builtIns.stream().map(value -> value.metadata().pluginId()).distinct().count()
            != this.builtIns.size()) {
            throw new IllegalArgumentException("duplicate Java built-in package");
        }
    }

    @Override
    public RuntimeId id() {
        return RUNTIME_ID;
    }

    @Override
    public String contractIdentity() {
        return CONTRACT_IDENTITY;
    }

    @Override
    public List<com.sstlfsj.fibra.engine.BuiltInPluginPackage> builtInPackages() {
        return builtIns.stream().map(JavaBuiltInPackage::metadata).toList();
    }

    @Override
    public RuntimeDriver create(RuntimeHostServices services) {
        return new JavaRuntimeDriver(Objects.requireNonNull(services, "services"), builtIns);
    }
}
