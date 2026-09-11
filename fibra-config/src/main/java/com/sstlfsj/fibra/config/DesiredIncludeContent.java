package com.sstlfsj.fibra.config;

import java.util.List;

public sealed interface DesiredIncludeContent permits DesiredIncludeContent.Collected,
    DesiredIncludeContent.Uncollected {
    record Collected(List<DesiredInputNode> children) implements DesiredIncludeContent {
        public Collected {
            children = List.copyOf(children);
        }
    }

    record Uncollected() implements DesiredIncludeContent {
        public static final Uncollected INSTANCE = new Uncollected();
    }
}
