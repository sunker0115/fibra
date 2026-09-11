package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PropertyAccessor;
import com.sstlfsj.fibra.PropertyKey;
import com.sstlfsj.fibra.ServiceKey;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cordis associate.spec.ts 五项在 vNext 类型化 Properties 能力上的映射。 */
class AssociateSpecParityTest extends CordisSpecSupport {
    private static final ServiceKey<Foo> FOO = ServiceKey.of("foo", Foo.class);
    private static final ServiceKey<Bar> BAR = ServiceKey.of("foo.bar", Bar.class);
    private static final PropertyKey<Foo, Integer> BAR_PROPERTY =
        PropertyKey.of("foo.bar", Foo.class, Integer.class);

    @Test
    void serviceInjection() {
        root.services().provide(FOO, new Foo(1));
        var registration = root.services().provide(BAR, new Bar());
        var associated = root.properties().associate(root.services().require(FOO));
        assertEquals(1, associated.value().qux);
        assertSame(root.services().require(BAR),
            associated.caller().services().reference(BAR).value());
        registration.dispose().block(TIMEOUT);
        assertTrue(root.services().find(BAR).isEmpty());
    }

    @Test
    void propertyInjection() {
        root.properties().register(BAR_PROPERTY, new PropertyAccessor<>() {
            @Override
            public Integer get(com.sstlfsj.fibra.Context caller, Foo receiver) {
                return receiver.bar;
            }

            @Override
            public void set(com.sstlfsj.fibra.Context caller, Foo receiver,
                            Integer value) {
                receiver.bar = value;
            }
        });
        var foo = root.properties().associate(new Foo(1));
        foo.set(BAR_PROPERTY, 3);
        assertEquals(3, foo.get(BAR_PROPERTY));
    }

    @Test
    void associatedTypeServiceInjection() {
        var associated = root.withMetadata("scope", "caller")
            .properties().associate(new Session());
        var bar = new Bar();
        root.services().provide(BAR, bar);
        assertSame(bar, associated.caller().services().reference(BAR).value());
        assertEquals("caller", associated.caller().metadata("scope"));
    }

    @Test
    void associatedTypeAccessorInjection() {
        var key = PropertyKey.of("session.answer", Session.class, Integer.class);
        root.properties().register(key, new PropertyAccessor<>() {
            @Override
            public Integer get(com.sstlfsj.fibra.Context caller, Session receiver) {
                return receiver.answer;
            }

            @Override
            public void set(com.sstlfsj.fibra.Context caller, Session receiver,
                            Integer value) {
                receiver.answer = value + 1;
            }
        });
        var associated = root.properties().associate(new Session());
        associated.set(key, 100);
        assertEquals(101, associated.get(key));
    }

    @Test
    void inspect() {
        var calls = new AtomicInteger();
        var definition = PluginDefinition.builder("inspect", Class.class,
            () -> (context, type) -> {
                assertEquals("X", type.getSimpleName());
                calls.incrementAndGet();
                return Mono.empty();
            }).build();
        await(root.plugins().mount("inspect", definition.prepare(X.class)));
        assertEquals(1, calls.get());
    }

    private static final class Foo {
        private final int qux;
        private int bar;

        private Foo(int qux) {
            this.qux = qux;
        }
    }

    private static final class Bar {
    }

    private static final class Session {
        private int answer;
    }

    private static final class X {
    }
}
