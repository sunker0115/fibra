package com.sstlfsj.fibra.cli.api;

/** 一个 invocation 独占的受管终端事件循环。 */
public interface CliTerminalLease extends AutoCloseable {
    CliTerminalCapabilities capabilities();

    /** 阻塞运行 renderer，直到其结束、输入关闭、调用取消或失败。 */
    void run(CliTerminalRenderer renderer) throws Exception;

    @Override
    void close();
}
