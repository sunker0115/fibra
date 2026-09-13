package com.sstlfsj.fibra.cli;

/** 已映射为稳定 CLI 退出码的内部失败。 */
final class CliCommandFailure extends RuntimeException {
    private final int code;

    CliCommandFailure(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    int code() {
        return code;
    }
}
