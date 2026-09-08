package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.config.PluginContract;

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

    public C bind(Object literal) {
        var value = binder.apply(literal);
        return definition.validate(value);
    }

    PluginContract contract() {
        var builder = PluginContract.builder(definition.name())
            .configType(definition.configType())
            .binder(this::bind);
        definition.requires().keySet().forEach(builder::require);
        definition.provides().forEach(builder::provide);
        return builder.build();
    }
}
