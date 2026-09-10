package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.logging.LogExporter;
import com.sstlfsj.fibra.logging.LogLevel;
import com.sstlfsj.fibra.logging.LogMessage;
import com.sstlfsj.fibra.logging.LoggerIntercept;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LoggingCapabilityContractTest {
    private static final ServiceKey<Action> OUTER =
        ServiceKey.of("outer", Action.class);
    private static final ServiceKey<Action> INNER =
        ServiceKey.of("inner", Action.class);

    @Test
    void keepsBoundedBufferInPlaceAndChronological() {
        try (var runtime = FibraRuntime.create()) {
            var logging = runtime.rootScope().context().logging();
            var buffer = logging.buffer();
            logging.bufferSize(2);

            var logger = runtime.rootScope().context().logger();
            logger.info("one");
            logger.info("two");
            logger.info("three");

            assertSame(buffer, logging.buffer());
            assertEquals(List.of("two", "three"), buffer.stream()
                .map(message -> message.arguments().getFirst()).toList());
        }
    }

    @Test
    void exporterIsScopeOwnedAndLoggerNamesFollowInvocationNesting() {
        var captured = new ArrayList<LogMessage>();
        try (var runtime = FibraRuntime.create()) {
            var root = runtime.rootScope().context();
            var owner = runtime.rootScope().openChild("exporter");
            owner.context().logging().exporter(LogExporter.to(captured::add,
                LogLevel.DEBUG));
            root.services().provide(INNER,
                invocation -> invocation.logger().debug("inner"));
            root.services().provide(OUTER, invocation -> {
                invocation.service(INNER).invoke((innerInvocation, inner) -> {
                    inner.run(innerInvocation);
                    return null;
                });
                invocation.logger().debug("outer");
            });

            root.withLogger(new LoggerIntercept("caller", null))
                .services().reference(OUTER).invoke((invocation, outer) -> {
                    outer.run(invocation);
                    return null;
                });
            assertEquals(List.of("caller", "caller"), captured.stream()
                .map(LogMessage::name).toList());

            owner.close();
            root.logger().info("after");
            assertEquals(2, captured.size());
        }
    }

    @FunctionalInterface
    private interface Action {
        void run(com.sstlfsj.fibra.InvocationContext invocation);
    }
}
