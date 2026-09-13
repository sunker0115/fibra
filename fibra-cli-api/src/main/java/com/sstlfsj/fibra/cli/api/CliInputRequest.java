package com.sstlfsj.fibra.cli.api;

import java.util.Objects;

/** 一次未经 shell 解析、trim 或分词的应用级 REPL 提交。 */
public record CliInputRequest(String text, CliInvocation invocation) {
    public CliInputRequest {
        text = Objects.requireNonNull(text, "text");
        invocation = Objects.requireNonNull(invocation, "invocation");
    }
}
