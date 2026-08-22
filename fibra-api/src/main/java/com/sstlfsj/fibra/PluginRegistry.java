package com.sstlfsj.fibra;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/** 插件入口对象与其 Fibra 实例的身份注册表。 */
public interface PluginRegistry {
    int size();

    boolean has(Object pluginIdentity);

    List<Object> keys();

    List<List<Fibra>> values();

    Map<Object, List<Fibra>> entries();

    List<Fibra> fibras();

    List<Fibra> fibras(Object pluginIdentity);

    Mono<Void> remove(Object pluginIdentity);
}
