package com.sstlfsj.fibra;

/** 可同步收集多个清理动作的 effect；异常在注册调用点直接传播。 */
@FunctionalInterface
public interface SyncEffect {
    void apply(EffectSink sink);
}
