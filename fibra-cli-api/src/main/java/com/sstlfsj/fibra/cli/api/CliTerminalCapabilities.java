package com.sstlfsj.fibra.cli.api;

/** 当前终端租约可向 renderer 提供的稳定能力。 */
public record CliTerminalCapabilities(boolean color, boolean resize) {
}
