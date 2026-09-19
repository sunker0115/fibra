package com.sstlfsj.fibra.verification.external;

import java.util.Objects;

/**
 * 仅供 verification 测试切换外部 execution 的可达性。
 *
 * <p>上线只请求 Engine 重新 reconcile；夹具绝不自行启动 execution unit。离线同步撤销
 * route 并把 unit 观察切为 PENDING，但不会调用 stop 或释放 unit lease。</p>
 */
public final class ExternalFixtureRuntimeController {
    private final ExternalFixtureRuntimeDriver driver;

    ExternalFixtureRuntimeController(ExternalFixtureRuntimeDriver driver) {
        this.driver = Objects.requireNonNull(driver, "driver");
    }

    public void goOnline() {
        driver.setOnline(true);
    }

    public void goOffline() {
        driver.setOnline(false);
    }

    public ExternalFixtureRuntimeSnapshot snapshot() {
        return driver.fixtureSnapshot();
    }
}
