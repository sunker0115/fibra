package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

/** Cordis packages/core/tests/reflect.spec.ts 的 4 项逐条映射。 */
class ReflectSpecParityTest extends CordisSpecSupport {
    private static final ServiceKey<Box> FOO = ServiceKey.of("foo", Box.class);

    @Test
    void contextIs() {
        assertInstanceOf(Context.class, root);
        assertSame(root, root.root());
    }

    @Test
    void accessCheck() {
        assertThrows(IllegalArgumentException.class,
            () -> ServiceKey.of(" ", Box.class));
        root.provide(FOO, new Box(1));
        assertThrows(IllegalStateException.class, () -> root.provide(FOO, new Box(2)));
        root.set(FOO, new Box(3));
        assertEquals(3, root.get(FOO).value);
    }

    @Test
    void serviceInjection() {
        root.provide(FOO, new Box(1));
        var child = root.extend(java.util.Map.of("baz", 2));
        assertEquals(1, child.get(FOO).value);
        assertEquals(2, child.metadata("baz"));
    }

    @Test
    void serviceInjectLeak() {
        root.provide(FOO, new Box(1));
        var descriptor = PluginDescriptor.<Void>builder("consumer").require(FOO).build();
        var fibra = root.plugin(descriptor, (context, config) -> Mono.empty(), null);
        await(fibra);
        fibra.dispose().block();
        assertThrows(IllegalStateException.class,
            () -> fibra.context().service(FOO).invoke((invocation, value) -> value));
    }

    static final class Box {
        final int value;
        Box(int value) { this.value = value; }
    }
}
