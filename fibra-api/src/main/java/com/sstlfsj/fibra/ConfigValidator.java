package com.sstlfsj.fibra;

@FunctionalInterface
public interface ConfigValidator<C> {
    C validate(C config);
}
