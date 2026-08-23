package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.ServiceRegistration;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FibraServiceBridgeTest {
    interface Greeting { String greet(String n); }

    private Context root;

    @BeforeEach void setUp() { root = FibraRuntime.create(); }
    @AfterEach void tearDown() { root.close(); }

    @Test
    void registersHostBeanAsFibraServiceAndRevokes() {
        var key = ServiceKey.of("greeting", Greeting.class);
        var bridge = new FibraServiceBridge(root);

        ServiceRegistration<Greeting> reg = bridge.register(key, n -> "hi " + n);

        assertEquals("hi x", root.service(key).invoke((inv, svc) -> svc.greet("x")));
        reg.dispose().block();
        // 撤销后严格读取应视为不可用。真实 API 语义:strict get 命中被移除/非 ACTIVE 的
        // 绑定时返回 null(见 ReflectRegistry#getDirect / #revoke),并不抛异常。
        assertNull(root.get(key, true));
    }
}
