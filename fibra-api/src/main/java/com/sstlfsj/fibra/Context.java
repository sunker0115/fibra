package com.sstlfsj.fibra;

import com.sstlfsj.fibra.logging.FibraLogger;
import com.sstlfsj.fibra.logging.LoggerIntercept;
import com.sstlfsj.fibra.logging.LoggerService;

/** 绑定一个 {@link Scope} 的不可变能力视图，不拥有生命周期。 */
public interface Context {
    Scope scope();

    Services services();

    Effects effects();

    Events events();

    Plugins plugins();

    Properties properties();

    LoggerService logging();

    FibraLogger logger();

    FibraLogger logger(String name);

    FibraLogger loggerForService(String serviceName);

    Object metadata(String name);

    Context withMetadata(String name, Object value);

    Context withRealm(ServiceKey<?> key, Object label);

    Context withIntercept(ServiceKey<?> key, Object value);

    Context withLogger(LoggerIntercept value);

    Object intercept(ServiceKey<?> key);
}
