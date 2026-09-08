package com.sstlfsj.fibra;

import com.sstlfsj.fibra.logging.FibraLogger;

/** 绑定一个 {@link Scope} 的不可变能力视图，不拥有生命周期。 */
public interface Context {
    Scope scope();

    Services services();

    Effects effects();

    Events events();

    Plugins plugins();

    FibraLogger logger();

    Context withMetadata(String name, Object value);

    Context withRealm(ServiceKey<?> key, Object label);

    Context withIntercept(ServiceKey<?> key, Object value);

    Object intercept(ServiceKey<?> key);
}
