package com.sstlfsj.fibra.engine;

import java.util.Objects;

/** 远端 contribution gateway 在进入已发布路由前产生的稳定失败。 */
public final class RemoteContributionInvocationException extends RuntimeException {
    public enum Code {
        UNKNOWN_KIND,
        KIND_NOT_REMOTE,
        INPUT_CODEC,
        OUTPUT_CODEC
    }

    private final Code code;

    RemoteContributionInvocationException(Code code, String message,
                                          Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
