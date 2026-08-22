package com.sstlfsj.fibra.pf4j;

import com.sstlfsj.fibra.Plugin;
import org.pf4j.ExtensionPoint;

/** 一个 PF4J 插件制品对应的唯一 Fibra 生命周期入口。 */
public interface FibraPluginEntrypoint extends Plugin<Void>, ExtensionPoint {
}
