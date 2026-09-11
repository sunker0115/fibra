package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.logging.LogExporter;
import com.sstlfsj.fibra.logging.LogLevel;
import com.sstlfsj.fibra.logging.LogMessage;
import com.sstlfsj.fibra.logging.LoggerIntercept;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggerSpecParityTest extends CordisSpecSupport {
    private static final ServiceKey<LoggingService> FOO =
        ServiceKey.of("foo:driver", LoggingService.class);
    private static final ServiceKey<LoggingService> BAR =
        ServiceKey.of("bar:driver", LoggingService.class);
    private final List<LogMessage> captured = new ArrayList<>();

    @BeforeEach
    void captureAll() {
        root.logging().exporter(LogExporter.to(captured::add, LogLevel.DEBUG));
    }

    @Test
    void keepsBoundedBufferInPlaceAndChronological() {
        var buffer = root.logging().buffer();
        root.logging().bufferSize(2);
        root.logger().info("one");
        root.logger().info("two");
        root.logger().info("three");
        assertSame(buffer, root.logging().buffer());
        assertEquals(List.of("two", "three"), buffer.stream()
            .map(message -> message.arguments().getFirst()).toList());
        root.logging().bufferSize(0);
        root.logger().info("four");
        assertTrue(buffer.isEmpty());
    }

    @Test
    void disposesExporterThatRegisteredDisposer() {
        var local = new ArrayList<LogMessage>();
        var registration = root.logging().exporter(
            LogExporter.to(local::add, LogLevel.DEBUG));
        registration.dispose().block(TIMEOUT);
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
        root.withLogger(new LoggerIntercept("intercepted", null))
            .logger().debug("hello");
        assertEquals("intercepted", captured.getLast().name());
    }

    @Test
    void usesServiceNameInsideServiceMethod() {
        root.services().provide(FOO,
            invocation -> invocation.logger().debug("from action"));
        root.services().reference(FOO).invoke((invocation, service) -> {
            service.log(invocation);
            return null;
        });
        assertEquals("foo:driver", captured.getLast().name());
    }

    @Test
    void outerCallerInterceptOverridesServiceName() {
        root.services().provide(FOO,
            invocation -> invocation.logger().debug("from action"));
        root.withLogger(new LoggerIntercept("caller-override", null))
            .services().reference(FOO).invoke((invocation, service) -> {
                service.log(invocation);
                return null;
            });
        assertEquals("caller-override", captured.getLast().name());
    }

    @Test
    void usesInnermostServiceNameAndRestoresOuter() {
        root.services().provide(BAR,
            invocation -> invocation.logger().debug("from bar"));
        root.services().provide(FOO, invocation -> {
            invocation.service(BAR).invoke((inner, service) -> {
                service.log(inner);
                return null;
            });
            invocation.logger().debug("from foo");
        });
        root.services().reference(FOO).invoke((invocation, service) -> {
            service.log(invocation);
            return null;
        });
        assertEquals(List.of("bar:driver", "foo:driver"), captured.stream()
            .map(LogMessage::name).toList());
    }

    @Test
    void usesServiceNameInsideServiceInit() {
        var definition = PluginDefinition.builder("foo:driver", Void.class,
            () -> (context, config) -> {
                context.logger().debug("from init");
                return Mono.empty();
            }).build();
        await(root.plugins().mount("foo:driver", definition.prepare(null)));
        assertEquals("foo:driver", captured.getLast().name());
    }

    @FunctionalInterface
    private interface LoggingService {
        void log(InvocationContext invocation);
    }
}
