package com.sstlfsj.fibra.engine.deployment;

import java.util.Objects;

/** 目录查询的宿主错误码和安全诊断，不携带插件异常对象。 */
public final class DefinitionCatalogException extends RuntimeException {
    public enum Code {
        ENGINE_UNAVAILABLE,
        STALE_TARGET,
        NOT_SELECTED,
        PACKAGE_UNAVAILABLE,
        UNSUPPORTED_RUNTIME,
        READ_FAILED,
        CLEANUP_FAILED
    }

    private final Code code;

    public DefinitionCatalogException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() { return code; }
}
