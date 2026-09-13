package com.sstlfsj.fibra.cli.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * renderer 生成的一次完整、不可变物理画面。
 * 每个元素是一行且必须适配最近一次收到的终端宽度；样式只允许 ANSI SGR，光标使用显示单元格坐标。
 */
public record CliTerminalFrame(List<String> lines, Optional<CliTerminalCursor> cursor) {
    public CliTerminalFrame {
        lines = List.copyOf(lines);
        lines.forEach(CliTerminalFrame::validateLine);
        cursor = Objects.requireNonNull(cursor, "cursor");
        if (cursor.isPresent() && cursor.orElseThrow().row() >= lines.size()) {
            throw new IllegalArgumentException("terminal cursor row must address a frame line");
        }
    }

    private static void validateLine(String line) {
        Objects.requireNonNull(line, "terminal frame line");
        for (var index = 0; index < line.length(); index++) {
            var value = line.charAt(index);
            if (value == '\u001b') {
                index = sgrEnd(line, index);
                continue;
            }
            if (value < 0x20 || value == 0x7f || value >= 0x80 && value <= 0x9f) {
                throw new IllegalArgumentException(
                    "terminal frame lines may contain only text and ANSI SGR");
            }
        }
    }

    private static int sgrEnd(String line, int escape) {
        if (escape + 1 >= line.length() || line.charAt(escape + 1) != '[') {
            throw new IllegalArgumentException(
                "terminal frame lines may contain only text and ANSI SGR");
        }
        for (var index = escape + 2; index < line.length(); index++) {
            var value = line.charAt(index);
            if (value == 'm') return index;
            if (!(value >= '0' && value <= '9') && value != ';' && value != ':') {
                throw new IllegalArgumentException(
                    "terminal frame lines may contain only text and ANSI SGR");
            }
        }
        throw new IllegalArgumentException(
            "terminal frame lines may contain only text and ANSI SGR");
    }
}
