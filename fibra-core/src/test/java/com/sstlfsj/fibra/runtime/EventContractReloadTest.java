package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.EventMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import javax.tools.ToolProvider;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventContractReloadTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String SIGNAL_TYPE = "fixture.ReloadSignal";

    @ParameterizedTest
    @EnumSource(value = EventMode.class, names = {"PARALLEL", "SERIAL"})
    void releasesRetiredListenerTypeWithoutMixingCapturedDispatchSnapshots(
        EventMode mode, @org.junit.jupiter.api.io.TempDir Path work) throws Exception {
        var classes = compileListener(work);
        try (var runtime = FibraRuntime.create();
             var oldLoader = loader(classes);
             var newLoader = loader(classes);
             var domain = runtime.openDomain("reload")) {
            var events = domain.rootScope().context().events();
            var oldCalls = new AtomicInteger();
            var newCalls = new AtomicInteger();
            var oldKey = key(oldLoader.loadClass(SIGNAL_TYPE), mode);
            var newKey = key(newLoader.loadClass(SIGNAL_TYPE), mode);
            var oldListener = listener(oldKey.listenerType(), oldCalls);
            var newListener = listener(newKey.listenerType(), newCalls);
            var oldRegistration = events.on(oldKey, oldListener);
            var captured = dispatch(events, oldKey, mode);

            var conflict = assertThrows(IllegalArgumentException.class,
                () -> events.on(newKey, newListener));
            assertEquals("event \"reload\" has conflicting contracts", conflict.getMessage());

            oldRegistration.dispose().block(TIMEOUT);
            var retired = domain.snapshot().events().getFirst();
            assertEquals(SIGNAL_TYPE, retired.listenerType());
            assertEquals(mode, retired.mode());
            assertEquals(0, retired.listeners().size());

            events.on(newKey, newListener);
            captured.block(TIMEOUT);
            captured.block(TIMEOUT);
            assertEquals(2, oldCalls.get());
            assertEquals(0, newCalls.get());

            dispatch(events, newKey, mode).block(TIMEOUT);
            assertEquals(2, oldCalls.get());
            assertEquals(1, newCalls.get());
        }
    }

    private static Path compileListener(Path work) throws Exception {
        var source = work.resolve("fixture/ReloadSignal.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package fixture; public interface ReloadSignal { void call(); }");
        var classes = work.resolve("classes");
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(),
            source.toString()));
        return classes;
    }

    private static URLClassLoader loader(Path classes) throws Exception {
        return new URLClassLoader(new java.net.URL[] {classes.toUri().toURL()},
            ClassLoader.getPlatformClassLoader());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static EventKey<Object> key(Class<?> listenerType, EventMode mode) {
        return EventKey.of("reload", (Class) listenerType, mode);
    }

    private static Object listener(Class<?> listenerType, AtomicInteger calls) {
        return Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[] {listenerType},
            (proxy, method, arguments) -> {
                if (method.getName().equals("call")) {
                    calls.incrementAndGet();
                }
                return null;
            });
    }

    private static Mono<?> dispatch(com.sstlfsj.fibra.Events events, EventKey<Object> key,
                                    EventMode mode) {
        return mode == EventMode.PARALLEL
            ? events.parallel(key, EventContractReloadTest::call)
            : events.serial(key, EventContractReloadTest::call);
    }

    private static Publisher<?> call(Object listener) {
        try {
            listener.getClass().getMethod("call").invoke(listener);
            return Mono.empty();
        } catch (ReflectiveOperationException failure) {
            return Mono.error(failure);
        }
    }
}
