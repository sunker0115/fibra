package com.sstlfsj.fibra.verification.external;

import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.engine.BuiltInPluginPackage;
import com.sstlfsj.fibra.engine.RuntimeDriver;
import com.sstlfsj.fibra.engine.RuntimeHostServices;
import com.sstlfsj.fibra.engine.RuntimeProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verification-only observation harness around the reusable external provider.
 * RuntimeProvider conformance tests use {@link ExternalFixtureRuntimeProvider} directly.
 */
public final class ExternalFixtureRuntimeHarness {
    private final ExternalFixtureRuntimeProvider delegate =
        new ExternalFixtureRuntimeProvider();
    private final Map<String, ExternalFixtureRuntimeDriver> drivers =
        new ConcurrentHashMap<>();
    private final RuntimeProvider provider = new RuntimeProvider() {
        @Override public RuntimeId id() { return delegate.id(); }
        @Override public String contractIdentity() {
            return delegate.contractIdentity();
        }
        @Override public List<BuiltInPluginPackage> builtInPackages() {
            return delegate.builtInPackages();
        }
        @Override public RuntimeDriver create(RuntimeHostServices services) {
            var driver = (ExternalFixtureRuntimeDriver) delegate.create(services);
            var previous = drivers.putIfAbsent(services.hostInstanceId(), driver);
            if (previous != null) {
                driver.closeAsync().block();
                throw new IllegalStateException(
                    "external fixture host already has a driver: "
                        + services.hostInstanceId());
            }
            return driver;
        }
    };

    public RuntimeProvider provider() {
        return provider;
    }

    public ExternalFixtureRuntimeController controller() {
        if (drivers.size() != 1) {
            throw new IllegalStateException(
                "external fixture harness requires exactly one active host");
        }
        return new ExternalFixtureRuntimeController(
            drivers.values().iterator().next());
    }

    public ExternalFixtureRuntimeController controller(String hostInstanceId) {
        var driver = drivers.get(hostInstanceId);
        if (driver == null) {
            throw new IllegalStateException(
                "external fixture host has no driver: " + hostInstanceId);
        }
        return new ExternalFixtureRuntimeController(driver);
    }
}
