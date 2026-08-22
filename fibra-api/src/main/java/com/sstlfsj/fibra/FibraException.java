package com.sstlfsj.fibra;

import java.util.Objects;

/** 具有稳定机器可读错误码的 Fibra 运行时异常。 */
public final class FibraException extends IllegalStateException {
    public static final String CONTEXT_CLOSED = "CONTEXT_CLOSED";
    public static final String SERVICE_INACTIVE = "SERVICE_INACTIVE";
    public static final String SERVICE_DUPLICATE = "SERVICE_DUPLICATE";
    public static final String EFFECT_INACTIVE = "EFFECT_INACTIVE";

    private final String code;

    public FibraException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public FibraException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
