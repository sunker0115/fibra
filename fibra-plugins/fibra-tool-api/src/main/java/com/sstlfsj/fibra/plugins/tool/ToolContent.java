package com.sstlfsj.fibra.plugins.tool;

import java.util.Objects;

/** 工具结果的有序内容块；当前协议只支持文本。 */
public sealed interface ToolContent permits ToolContent.Text {
    static Text text(String text) {
        return new Text(text);
    }

    record Text(String text) implements ToolContent {
        public Text {
            Objects.requireNonNull(text, "text");
        }
    }
}
