package com.sstlfsj.fibra.cli.api;

/** renderer 可跨线程保存的控制柄；请求会被合并并在终端 lane 串行处理。 */
public interface CliTerminalControl {
    /** 请求用最新状态重绘；租约停止接收请求后返回 false。 */
    boolean requestRender();

    /** 请求正常结束事件循环；租约停止接收请求后返回 false。 */
    boolean finish();
}
