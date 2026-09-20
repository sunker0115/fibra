package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.engine.ExecutionObservation;

/** 完整 desired entry id 下的 raw desired 与当前执行事实；任一侧可以暂缺。 */
public record RegistryPluginState(String entryId, DesiredInputEntry desired,
                                  ExecutionObservation observed) { }
