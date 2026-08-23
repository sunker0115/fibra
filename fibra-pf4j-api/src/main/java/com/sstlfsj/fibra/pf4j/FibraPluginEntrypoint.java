package com.sstlfsj.fibra.pf4j;

import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDescriptor;
import org.pf4j.ExtensionPoint;

/** 一个 PF4J 插件制品对应的 Fibra 运行实例工厂。 */
public interface FibraPluginEntrypoint<C> extends ExtensionPoint {
    Class<C> configType();

    PluginDescriptor<C> descriptor(String entryId);

    Plugin<C> create(String entryId);
}
