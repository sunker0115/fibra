package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.PluginInstanceState;

/** 已启用声明的运行状态达成要求，不给动态子插件附加声明策略。 */
public enum PublicationRequirement {
    ACTIVE_REQUIRED,
    PENDING_ALLOWED;

    public boolean accepts(PluginInstanceState state) {
        return state == PluginInstanceState.ACTIVE
            || this == PENDING_ALLOWED && state == PluginInstanceState.PENDING;
    }
}
