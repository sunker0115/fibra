package com.sstlfsj.fibra.config;

/** 配置合同拒绝；仅保留固定错误码，不持有源文档或第三方异常。 */
public final class ConfigSchemaException extends IllegalArgumentException {
    public enum Code { UNSUPPORTED_PROFILE, INVALID_SCHEMA, BUDGET_EXCEEDED }

    private final Code code;

    ConfigSchemaException(Code code) {
        super(code.name(), null);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
