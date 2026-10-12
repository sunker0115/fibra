package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Objects;

/** 批量校验中的一次实例出现；共享 source 不免除实例计费。 */
public record ConfigValidationInput(ConfigSchemaSource source, LiteralValue effectiveValue) {
    public ConfigValidationInput {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(effectiveValue, "effectiveValue");
    }
}
