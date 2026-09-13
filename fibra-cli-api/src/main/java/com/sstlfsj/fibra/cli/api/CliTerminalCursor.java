package com.sstlfsj.fibra.cli.api;

/** renderer 物理帧内的零基光标单元格；column 按终端显示列宽计数，不是 Java 字符索引。 */
public record CliTerminalCursor(int row, int column) {
    public CliTerminalCursor {
        if (row < 0 || column < 0) {
            throw new IllegalArgumentException("terminal cursor must be non-negative");
        }
    }
}
