package com.sstlfsj.fibra.cli.api;

/**
 * 由 Fibra 终端 lane 串行调用的通用渐进式 renderer。
 * start、input、resize、render 和 stop 不会并发执行；start 一旦被调用，即使失败也会调用一次 stop。
 */
public interface CliTerminalRenderer {
    default void start(CliTerminalControl control) throws Exception {
    }

    /** 处理输入；正常返回后框架自动请求一帧最新状态。 */
    default void input(CliTerminalInput input) throws Exception {
    }

    default void resize(CliTerminalSize size) throws Exception {
    }

    CliTerminalFrame render(CliTerminalSize size) throws Exception;

    default void stop() throws Exception {
    }
}
