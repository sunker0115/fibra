package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.HostServiceRegistry;
import com.sstlfsj.fibra.engine.PluginCatalog;
import com.sstlfsj.fibra.engine.PluginCatalogEntry;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraServiceExporterTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exportsTheOriginalSpringProxyAndPreservesAdvice(boolean classProxy) {
        var calls = new AtomicInteger();
        var factory = new ProxyFactory(new GreetingService());
        factory.setProxyTargetClass(classProxy);
        factory.addAdvice((MethodInterceptor) invocation -> {
            calls.incrementAndGet();
            return invocation.proceed();
        });
        var proxy = (Greeting) factory.getProxy();
        assertTrue(classProxy ? AopUtils.isCglibProxy(proxy) : AopUtils.isJdkDynamicProxy(proxy));
        var observed = new AtomicReference<Greeting>();
        var consumer = PluginDefinition.builder("consumer", Void.class,
            () -> (context, config) -> {
                observed.set(context.services().find(ServiceKey.of("greeting", Greeting.class))
                    .orElse(null));
                return Mono.empty();
            }).build();
        var desired = new DesiredInputGraph(List.of(
            DesiredInputEntry.builder("consumer", "consumer").build()));
        var hostServices = new HostServiceRegistry();
        var exporter = new FibraServiceExporter(new FibraServiceBridge(hostServices));

        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(desired))
            .hostServices(hostServices)
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(consumer, ignored -> null)))
            .build()) {
            assertSame(proxy, exporter.postProcessAfterInitialization(proxy, "greeting"));
            engine.start().block(Duration.ofSeconds(5));

            assertSame(proxy, observed.get());
            assertEquals("hello fibra", observed.get().greet("fibra"));
            assertEquals(1, calls.get());
        }
    }

    interface Greeting {
        String greet(String name);
    }

    @FibraService(name = "greeting", type = Greeting.class)
    static class GreetingService implements Greeting {
        @Override
        public String greet(String name) {
            return "hello " + name;
        }
    }
}
