package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Objects;
import java.util.function.Function;

public final class PluginCatalogEntry<C> {
    private final PluginDefinition<C> definition;
    private final Function<Object, C> binder;

    public PluginCatalogEntry(PluginDefinition<C> definition, Function<Object, C> binder) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.binder = Objects.requireNonNull(binder, "binder");
    }

    public PluginDefinition<C> definition() {
        return definition;
    }

    public PluginDefinition.Prepared<C> bind(LiteralValue literal) {
        var value = binder.apply(Objects.requireNonNull(literal, "literal").toJava());
        return definition.prepare(value);
    }
}
