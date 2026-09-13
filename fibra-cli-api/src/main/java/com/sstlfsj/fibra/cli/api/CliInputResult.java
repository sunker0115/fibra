package com.sstlfsj.fibra.cli.api;

import java.util.Objects;

/** 一次应用文本提交的命令结果与 REPL 控制结果。 */
public record CliInputResult(CliCommandResult commandResult, boolean exitRequested) {
    public CliInputResult {
        Objects.requireNonNull(commandResult, "commandResult");
    }

    public static CliInputResult continueWith(CliCommandResult result) {
        return new CliInputResult(result, false);
    }

    public static CliInputResult exitWith(CliCommandResult result) {
        return new CliInputResult(result, true);
    }
}
