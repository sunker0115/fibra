package com.sstlfsj.fibra.pf4j;

import com.sstlfsj.fibra.PluginDescriptor;

/** 不接收配置的 PF4J Fibra 运行实例工厂。 */
public interface VoidFibraPluginEntrypoint extends FibraPluginEntrypoint<Void> {
    @Override
    default Class<Void> configType() {
        return Void.class;
    }

    @Override
    default PluginDescriptor<Void> descriptor(String entryId) {
        return PluginDescriptor.<Void>builder(entryId).build();
    }
}
