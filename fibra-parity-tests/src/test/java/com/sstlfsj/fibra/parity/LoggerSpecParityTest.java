package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.*;
import com.sstlfsj.fibra.logging.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Cordis packages/core/tests/logger.spec.ts 的 9 项逐条映射。 */
class LoggerSpecParityTest extends CordisSpecSupport {
    private static final ServiceKey<LoggingService> FOO = ServiceKey.of("foo:driver", LoggingService.class);
    private static final ServiceKey<LoggingService> BAR = ServiceKey.of("bar:driver", LoggingService.class);
    private final List<LogMessage> captured = new ArrayList<>();

    @BeforeEach
    void captureAll() {
        root.logging().exporter(LogExporter.to(captured::add, LogLevel.DEBUG));
    }

    @Test
    void keepsBoundedBufferInPlaceAndChronological() {
        var buffer = root.logging().buffer();
        root.logging().bufferSize(2);
        root.logger().info("one"); root.logger().info("two"); root.logger().info("three");
        assertSame(buffer, root.logging().buffer());
        assertEquals(List.of("two", "three"), buffer.stream().map(m -> m.arguments().getFirst()).toList());
        root.logging().bufferSize(0); root.logger().info("four");
        assertTrue(buffer.isEmpty());
    }

    @Test
    void disposesExporterThatRegisteredDisposer() {
        var local = new ArrayList<LogMessage>();
        var registration = root.logging().exporter(LogExporter.to(local::add, LogLevel.DEBUG));
        registration.dispose().block();
        root.logger().info("test");
        assertTrue(local.isEmpty());
    }

    @Test
    void usesFibraNameOutsideService() {
        root.logger().debug("hello");
        assertEquals("root", captured.getLast().name());
    }

    @Test
    void honoursExplicitNameArgument() {
        root.logger("custom").debug("hello");
        assertEquals("custom", captured.getLast().name());
    }

    @Test
    void honoursInterceptName() {
        root.intercept("logger", new LoggerIntercept("intercepted", null)).logger().debug("hello");
        assertEquals("intercepted", captured.getLast().name());
    }

    @Test
    void usesServiceNameInsideServiceMethod() {
        root.provide(FOO, invocation -> invocation.logger().debug("from action"));
        root.service(FOO).invoke((invocation, service) -> { service.log(invocation); return null; });
        assertEquals("foo:driver", captured.getLast().name());
    }

    @Test
    void outerCallerInterceptOverridesServiceName() {
        root.provide(FOO, invocation -> invocation.logger().debug("from action"));
        var caller = root.intercept("logger", new LoggerIntercept("caller-override", null));
        caller.service(FOO).invoke((invocation, service) -> { service.log(invocation); return null; });
        assertEquals("caller-override", captured.getLast().name());
    }

    @Test
    void usesInnermostServiceNameAndRestoresOuter() {
        root.provide(BAR, invocation -> invocation.logger().debug("from bar"));
        root.provide(FOO, invocation -> {
            invocation.service(BAR).invoke((inner, service) -> { service.log(inner); return null; });
            invocation.logger().debug("from foo");
        });
        root.service(FOO).invoke((invocation, service) -> { service.log(invocation); return null; });
        assertEquals(List.of("bar:driver", "foo:driver"),
            captured.stream().map(LogMessage::name).toList());
    }

    @Test
    void usesServiceNameInsideServiceInit() {
        var descriptor = PluginDescriptor.<Void>builder("foo:driver").build();
        var fibra = root.plugin(descriptor, (context, config) -> {
            context.logger().debug("from init");
            return Mono.empty();
        }, null);
        await(fibra);
        assertEquals("foo:driver", captured.getLast().name());
    }

    @FunctionalInterface interface LoggingService { void log(InvocationContext invocation); }
}
