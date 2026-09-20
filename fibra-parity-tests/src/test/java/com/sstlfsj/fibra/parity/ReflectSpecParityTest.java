package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.Scope;
import com.sstlfsj.fibra.ServiceKey;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectSpecParityTest extends CordisSpecSupport {
    private static final ServiceKey<Box> FOO = ServiceKey.of("foo", Box.class);

    @Test
    void contextIs() {
        assertInstanceOf(Context.class, root);
        assertNotSame(runtime.rootScope(), root.scope());
        assertFalse(root.scope() instanceof Scope);
        assertSame(root, root.scope().context());
        assertTrue(runtime.rootScope().sharesDomainWith(root.scope()));
    }

    @Test
    void accessCheck() {
        assertThrows(IllegalArgumentException.class,
            () -> ServiceKey.of(" ", Box.class));
        var first = root.services().provide(FOO, new Box(1));
        assertThrows(IllegalStateException.class,
            () -> root.services().provide(FOO, new Box(2)));
        first.dispose().block(TIMEOUT);
        root.services().provide(FOO, new Box(3));
        assertEquals(3, root.services().require(FOO).value);
    }

    @Test
    void serviceInjection() {
        root.services().provide(FOO, new Box(1));
        var child = root.withMetadata("baz", 2);
        assertEquals(1, child.services().require(FOO).value);
        assertEquals(2, child.metadata("baz"));
    }

    @Test
    void serviceInjectLeak() {
        root.services().provide(FOO, new Box(1));
        var definition = PluginDefinition.builder("consumer", Void.class,
                () -> (context, config) -> Mono.empty())
            .require(FOO).build();
        var instance = root.plugins().mount("consumer", definition.prepare(null));
        await(instance);
        instance.dispose().block(TIMEOUT);
        assertThrows(IllegalStateException.class,
            () -> instance.context().services().reference(FOO)
                .invoke((invocation, value) -> value));
    }

    private static final class Box {
        private final int value;

        private Box(int value) {
            this.value = value;
        }
    }
}
