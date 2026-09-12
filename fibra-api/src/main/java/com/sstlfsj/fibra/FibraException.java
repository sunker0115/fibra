package com.sstlfsj.fibra;

import java.util.Objects;

/** 具有稳定机器可读错误码的 Fibra 运行时异常。 */
public final class FibraException extends IllegalStateException {
    public static final String SCOPE_CLOSED = "SCOPE_CLOSED";
    public static final String RUNTIME_CLOSED = "RUNTIME_CLOSED";
    public static final String SERVICE_INACTIVE = "SERVICE_INACTIVE";
    public static final String SERVICE_DUPLICATE = "SERVICE_DUPLICATE";
    public static final String EFFECT_INACTIVE = "EFFECT_INACTIVE";
    public static final String PLUGIN_DUPLICATE = "PLUGIN_DUPLICATE";
    public static final String PLUGIN_DISPOSED = "PLUGIN_DISPOSED";
    public static final String PLUGIN_BATCH_UPDATE_FAILED = "PLUGIN_BATCH_UPDATE_FAILED";
    public static final String PLUGIN_UNDECLARED_SERVICE = "PLUGIN_UNDECLARED_SERVICE";
    public static final String PLUGIN_DISABLE_UNAVAILABLE = "PLUGIN_DISABLE_UNAVAILABLE";

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
