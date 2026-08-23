package com.sstlfsj.fibra.loader.pf4j;

/**
 * 在当前插件 ClassLoader 的配置类已知时创建一份新配置对象。
 *
 * <p>JAR reload 会使原插件私有配置类失效，因此动态配置必须从中立值重建，
 * 不能复用旧 ClassLoader 创建的对象。</p>
 */
@FunctionalInterface
public interface PluginConfigFactory {
    Object create(Class<?> configType);
}
