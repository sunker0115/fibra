package com.sstlfsj.fibra.cli.api;

/** 终端的可见字符网格，列数和行数都为正数。 */
public record CliTerminalSize(int columns, int rows) {
    public CliTerminalSize {
        if (columns <= 0 || rows <= 0) {
            throw new IllegalArgumentException("terminal size must be positive");
        }
    }
}
