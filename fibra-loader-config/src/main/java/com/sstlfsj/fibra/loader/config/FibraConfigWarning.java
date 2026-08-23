package com.sstlfsj.fibra.loader.config;

import java.util.Objects;

/** 不阻止候选配置继续解析的结构化诊断。 */
public record FibraConfigWarning(String code, String message, String entryId) {
    public FibraConfigWarning {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
