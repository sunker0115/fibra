package com.sstlfsj.fibra;

import java.util.List;

public record EffectMetadata(String label, List<EffectMetadata> children) {
    public EffectMetadata {
        children = List.copyOf(children);
    }
}
