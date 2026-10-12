package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Objects;

/** 不含运行值的不可变作者合同；构造不执行 schema 语义检查。 */
public record ConfigSchemaSource(String profile, LiteralValue.ObjectValue schema) {
    public ConfigSchemaSource {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(schema, "schema");
        SchemaBudget.source(schema);
    }
}
