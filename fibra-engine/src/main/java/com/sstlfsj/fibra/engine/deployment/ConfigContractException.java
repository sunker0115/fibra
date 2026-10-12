package com.sstlfsj.fibra.engine.deployment;

import java.util.Objects;

/** 配置合同边界的安全分类；不携带底层异常或实例输入。 */
public final class ConfigContractException extends RuntimeException {
    public enum Code {
        ENGINE_UNAVAILABLE, STALE_TARGET, NOT_SELECTED, PACKAGE_UNAVAILABLE,
        UNSUPPORTED_RUNTIME, UNKNOWN_DEFINITION, MISSING_CONTRACT, INVALID_CONTRACT,
        READ_FAILED, CLEANUP_FAILED
    }
    private final Code code;

    public ConfigContractException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() { return code; }
}
