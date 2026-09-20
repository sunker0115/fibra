package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.engine.RuntimeDriver;
import com.sstlfsj.fibra.engine.RuntimeHostServices;
import com.sstlfsj.fibra.engine.RuntimeProvider;

import java.util.List;
import java.util.Objects;

/** Node payload 与 sidecar 生命周期的唯一 runtime owner。 */
public final class NodeRuntimeProvider implements RuntimeProvider {
    public static final RuntimeId RUNTIME_ID = new RuntimeId("node");
    public static final String CONTRACT_IDENTITY = "fibra-runtime-node:task10-v1";

    private final NodeRuntimeOptions options;

    public NodeRuntimeProvider(NodeRuntimeOptions options) {
        this.options = Objects.requireNonNull(options, "options");
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
        return List.of();
    }

    @Override
    public RuntimeDriver create(RuntimeHostServices services) {
        return new NodeRuntimeDriver(Objects.requireNonNull(services, "services"),
            options);
    }
}
