package com.sstlfsj.fibra;

import java.util.Objects;

/**
 * Scope 的非拥有型稳定视图。
 *
 * <p>视图可以创建由调用者拥有的子 Scope，但不公开当前 Scope 的关闭能力。</p>
 */
public interface ScopeView {
    String name();

    Context context();

    Scope openChild(String name);

    /** 判断另一个 Scope 视图是否属于同一 RuntimeDomain。 */
    default boolean sharesDomainWith(ScopeView other) {
        return this == Objects.requireNonNull(other, "other");
    }

    boolean isClosed();
}
