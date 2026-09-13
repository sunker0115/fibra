package com.sstlfsj.fibra.cli.api;

/** 应用级 REPL 文本入口；每次调用拥有一个有限的 CLI invocation。 */
@FunctionalInterface
public interface CliInputHandler {
    CliInputResult invoke(CliInputRequest request) throws Exception;
}
