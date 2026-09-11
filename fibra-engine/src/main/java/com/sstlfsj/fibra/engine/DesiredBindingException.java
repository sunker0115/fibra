package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.config.ConfigDiagnostic;

import java.util.Objects;

/** 目标运行代不能绑定某个启用的输入声明。 */
public final class DesiredBindingException extends IllegalArgumentException {
    private final ConfigDiagnostic diagnostic;

    DesiredBindingException(ConfigDiagnostic diagnostic, Throwable cause) {
        super(diagnostic.message(), cause);
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    public ConfigDiagnostic diagnostic() { return diagnostic; }
}
