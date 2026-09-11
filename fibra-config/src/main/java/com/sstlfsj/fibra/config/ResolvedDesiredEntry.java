package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Objects;
import java.util.Optional;

/** 单个 raw desired 节点在指定上下文中的有效状态。 */
public final class ResolvedDesiredEntry {
    private final DesiredInputNode input;
    private final DesiredInputGraph.EffectiveDesiredEntry effective;
    private final Optional<LiteralValue> resolvedConfig;

    ResolvedDesiredEntry(DesiredInputNode input,
                         DesiredInputGraph.EffectiveDesiredEntry effective,
                         Optional<LiteralValue> resolvedConfig) {
        this.input = Objects.requireNonNull(input, "input");
        this.effective = Objects.requireNonNull(effective, "effective");
        this.resolvedConfig = Objects.requireNonNull(resolvedConfig, "resolvedConfig");
        if (!(input instanceof DesiredInputEntry) && resolvedConfig.isPresent()) {
            throw new IllegalArgumentException("only plugin entries have resolved config");
        }
    }

    public DesiredInputNode input() {
        return input;
    }

    public DesiredInputGraph.EffectiveDesiredEntry effective() {
        return effective;
    }

    public Optional<LiteralValue> resolvedConfig() {
        return resolvedConfig;
    }
}
