package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.logging.LogExporter;
import com.sstlfsj.fibra.logging.LogLevel;
import com.sstlfsj.fibra.logging.LogMessage;
import com.sstlfsj.fibra.logging.LoggerIntercept;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LoggerParityTest {
    private static final ServiceKey<OuterApi> OUTER = ServiceKey.of("outer", OuterApi.class);
    private static final ServiceKey<InnerApi> INNER = ServiceKey.of("inner", InnerApi.class);

    @Test
    void keepsTheBoundedBufferObjectInChronologicalOrder() {
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            var buffer = context.logging().buffer();
            context.logging().bufferSize(2);

            context.logger().info("one");
            context.logger().info("two");
            context.logger().info("three");

            assertSame(buffer, context.logging().buffer());
            assertEquals(List.of("two", "three"), buffer.stream()
                .map(message -> message.arguments().getFirst()).toList());
            context.logging().bufferSize(0);
            context.logger().info("four");
            assertEquals(List.of(), buffer);
        }
    }

    @Test
    void exporterDisposalRemovesTheExporterThatOwnsTheHandle() {
        var first = new ArrayList<LogMessage>();
        var second = new ArrayList<LogMessage>();
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            var firstHandle = context.logging().exporter(LogExporter.to(first::add));
            var secondHandle = context.logging().exporter(LogExporter.to(second::add));

            firstHandle.dispose().block();
            context.logger().info("value");

            assertEquals(0, first.size());
            assertEquals(1, second.size());
            secondHandle.dispose().block();
        }
    }

    @Test
    void resolvesExplicitInterceptFibraAndNestedServiceNames() {
        var captured = new ArrayList<LogMessage>();
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            context.logging().exporter(LogExporter.to(captured::add, LogLevel.DEBUG));
            context.logger().debug("root");
            context.logger("explicit").debug("explicit");
            context.withLogger(new LoggerIntercept("intercepted", null))
                .logger().debug("intercepted");
            context.services().provide(INNER,
                invocation -> invocation.logger().debug("inner"));
            context.services().provide(OUTER, invocation -> {
                invocation.service(INNER).invoke((innerInvocation, inner) -> {
                    inner.run(innerInvocation);
                    return null;
                });
                invocation.logger().debug("outer");
            });
            context.services().reference(OUTER).invoke((invocation, outer) -> {
                outer.run(invocation);
                return null;
            });
        }

        assertEquals(List.of("root", "explicit", "intercepted", "inner", "outer"),
            captured.stream().map(LogMessage::name).toList());
    }

    @Test
    void exporterLevelFiltersDebugButKeepsErrors() {
        var captured = new ArrayList<LogMessage>();
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            context.logging().exporter(LogExporter.to(captured::add, LogLevel.INFO));
            context.logger().debug("hidden");
            context.logger().error("visible");
        }

        assertEquals(List.of("visible"), captured.stream()
            .map(message -> message.arguments().getFirst()).toList());
    }

    @Test
    void exporterBelongsToTheCallingPluginFibra() {
        var captured = new ArrayList<LogMessage>();
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            var definition = PluginDefinition.builder("exporter-owner", Void.class,
                () -> (pluginContext, ignored) -> {
                    pluginContext.logging().exporter(LogExporter.to(captured::add));
                    return Mono.empty();
                }).build();
            var plugin = context.plugins().mount("exporter-owner", definition.prepare(null));
            plugin.settled().block();

            context.logger().info("before");
            plugin.dispose().block();
            context.logger().info("after");
        }

        assertEquals(List.of("before"), captured.stream()
            .map(message -> message.arguments().getFirst()).toList());
    }

    @FunctionalInterface
    private interface OuterApi {
        void run(InvocationContext invocation);
    }

    @FunctionalInterface
    private interface InnerApi {
        void run(InvocationContext invocation);
    }
}
