package com.sstlfsj.fibra.internal;

interface ResourceOwner {
    long identity();

    DefaultRuntimeDomain domain();

    DefaultScope scope();

    LifecycleDispatcher lifecycle();

    boolean acceptsResources();

    String ownerName();

    void addResource(OwnedEffect resource);

    void removeResource(OwnedEffect resource);

    void resourceFailed(Throwable failure);

    default PluginInstanceImpl<?> pluginInstance() {
        return null;
    }
}
