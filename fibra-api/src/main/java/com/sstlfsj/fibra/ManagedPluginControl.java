package com.sstlfsj.fibra;

/** 由宿主提供的、供插件请求停用自身的窄控制面。 */
@FunctionalInterface
public interface ManagedPluginControl {
    ServiceKey<ManagedPluginControl> KEY = ServiceKey.of("fibra.managed-plugin-control",
        ManagedPluginControl.class);

    void requestDisable(PluginInstance<?> instance);
}
