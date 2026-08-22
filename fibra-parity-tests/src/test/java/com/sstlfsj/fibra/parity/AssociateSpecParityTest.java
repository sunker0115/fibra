package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Cordis packages/core/tests/associate.spec.ts 的 5 项逐条映射。 */
class AssociateSpecParityTest extends CordisSpecSupport {
    private static final ServiceKey<Foo> FOO = ServiceKey.of("foo", Foo.class);
    private static final ServiceKey<Bar> BAR = ServiceKey.of("foo.bar", Bar.class);

    @Test
    void serviceInjection() {
        root.provide(FOO, new Foo(1));
        var bar = root.provide(BAR, new Bar());
        assertEquals(1, root.associate(root.get(FOO)).value().qux);
        assertInstanceOf(Bar.class, root.get(BAR));
        bar.dispose().block();
        assertNull(root.get(BAR));
    }

    @Test
    void propertyInjection() {
        var key = PropertyKey.of("foo.bar", Foo.class, Integer.class);
        root.accessor(key, new PropertyAccessor<>() {
            public Integer get(Context context, Foo receiver) { return receiver.bar; }
            public void set(Context context, Foo receiver, Integer value) { receiver.bar = value; }
        });
        var foo = new Foo(1);
        root.associate(foo).set(key, 3);
        assertEquals(3, root.associate(foo).get(key));
    }

    @Test
    void associatedTypeServiceInjection() {
        var session = root.extend(java.util.Map.of("scope", "caller")).associate(new Session());
        root.provide(BAR, new Bar());
        assertSame(session.caller().get(BAR), session.caller().service(BAR).value());
        assertEquals("caller", session.caller().metadata("scope"));
    }

    @Test
    void associatedTypeAccessorInjection() {
        var key = PropertyKey.of("session.answer", Session.class, Integer.class);
        root.accessor(key, new PropertyAccessor<>() {
            public Integer get(Context context, Session receiver) { return receiver.answer; }
            public void set(Context context, Session receiver, Integer value) { receiver.answer = value + 1; }
        });
        var associated = root.associate(new Session());
        associated.set(key, 100);
        assertEquals(101, associated.get(key));
    }

    @Test
    void inspect() {
        var calls = new AtomicInteger();
        var descriptor = PluginDescriptor.<Class<?>>builder("inspect").build();
        var fibra = root.plugin(descriptor, (context, type) -> {
            assertEquals("X", type.getSimpleName());
            calls.incrementAndGet();
            return Mono.empty();
        }, X.class);
        await(fibra);
        assertEquals(1, calls.get());
    }

    static final class Foo {
        final int qux;
        int bar;
        Foo(int qux) { this.qux = qux; }
    }
    static final class Bar {}
    static final class Session { int answer; }
    static final class X {}
}
