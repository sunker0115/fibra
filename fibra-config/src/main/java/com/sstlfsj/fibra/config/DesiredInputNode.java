package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Map;

/** 配置文件中的不可变输入节点。ID 在 include 命名空间内局部唯一。 */
public sealed interface DesiredInputNode permits DesiredInputEntry, DesiredInputGroup,
    DesiredInputInclude {
    String id();
    boolean enabled();
    Map<String, LiteralValue> realms();
    Map<String, LiteralValue> intercepts();

    static String requireId(String value) {
        if (value == null || value.isBlank() || value.indexOf(':') >= 0) {
            throw new IllegalArgumentException("id must be non-blank and must not contain ':'");
        }
        return value;
    }
}
