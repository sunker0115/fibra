package com.sstlfsj.fibra.spring;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraServiceExporterTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void exportsTheOriginalSpringProxyAndPreservesAdvice(boolean classProxy) {
        var calls = new AtomicInteger();
        var factory = new ProxyFactory(new GreetingService());
        factory.setProxyTargetClass(classProxy);
        factory.addAdvice((MethodInterceptor) invocation -> {
            calls.incrementAndGet();
            return invocation.proceed();
        });
        var proxy = (Greeting) factory.getProxy();
        var exporter = new FibraServiceExporter(new FibraServiceBridge(
            new com.sstlfsj.fibra.engine.HostServiceRegistry()));

        assertTrue(classProxy ? AopUtils.isCglibProxy(proxy) : AopUtils.isJdkDynamicProxy(proxy));
        assertSame(proxy, exporter.postProcessAfterInitialization(proxy, "greeting"));
        assertEquals("hello fibra", proxy.greet("fibra"));
        assertEquals(1, calls.get());
        exporter.stop();
    }

    interface Greeting { String greet(String name); }
    @FibraService(name = "greeting", type = Greeting.class)
    static class GreetingService implements Greeting {
        @Override public String greet(String name) { return "hello " + name; }
    }
}
