package com.sstlfsj.fibra;

import java.util.List;
import java.util.Optional;

public interface Plugins {
    Optional<PluginInstance<?>> current();

    /** 请求托管控制面持久停用当前条目；请求异步执行，不直接销毁实例。 */
    void requestDisable();

    <C> PluginInstance<C> mount(String instanceId, PluginDefinition.Prepared<C> prepared);

    Optional<PluginInstance<?>> find(String instanceId);

    List<PluginInstance<?>> instances();
}
