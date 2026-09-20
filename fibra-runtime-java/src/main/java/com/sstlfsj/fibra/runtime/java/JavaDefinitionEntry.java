package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Objects;
import java.util.function.Function;

/** Java runtime 私有的 definition、factory 与配置绑定契约。 */
public final class JavaDefinitionEntry<C> {
    private final PluginDefinition<C> definition;
    private final Function<Object, C> binder;

    public JavaDefinitionEntry(PluginDefinition<C> definition,
                               Function<Object, C> binder) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.binder = Objects.requireNonNull(binder, "binder");
    }

    public PluginDefinition<C> definition() {
        return definition;
    }

    public PluginDefinition.Prepared<C> bind(LiteralValue literal) {
        return definition.prepare(binder.apply(Objects.requireNonNull(literal,
            "literal").toJava()));
    }
}
