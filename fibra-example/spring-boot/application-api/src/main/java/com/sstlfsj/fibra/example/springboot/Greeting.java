package com.sstlfsj.fibra.example.springboot;

import com.sstlfsj.fibra.ServiceKey;

/**
 * 宿主定义并消费的问候 SPI；由上传的插件实现。
 *
 * <p>SPI 归属宿主公共 API（本模块），插件以 {@code provided} scope 依赖它并在运行时
 * 由 PF4J 类加载器把 {@code com.sstlfsj.fibra.*} 前缀委派回宿主父加载器，保证宿主与
 * 插件看到同一份 {@link Greeting} 类型。
 */
@FunctionalInterface
public interface Greeting {
    /** 宿主与插件共享的服务键；名称与类型必须两侧一致。 */
    ServiceKey<Greeting> KEY = ServiceKey.of("greeting", Greeting.class);

    String greet(String name);
}
