package com.sstlfsj.fibra.loader.pf4j;

import org.junit.jupiter.api.Test;
import org.pf4j.DefaultExtensionFinder;
import org.pf4j.DefaultPluginDescriptor;
import org.pf4j.DefaultPluginManager;
import org.pf4j.DefaultVersionManager;
import org.pf4j.DependencyResolver;
import org.pf4j.ExtensionWrapper;
import org.pf4j.LegacyExtensionFinder;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Pf4j313BehaviorTest {

    @Test
    void dependencyResolverOmitsExistingOptionalDependencyAndItsWrongVersion() {
        var resolver = new DependencyResolver(new DefaultVersionManager());
        var provider = descriptor("provider", "2.0.0", "");
        var consumer = descriptor("consumer", "1.0.0", "provider?@>=1.0.0 & <2.0.0");

        var result = resolver.resolve(List.of(provider, consumer));

        assertTrue(result.isOK());
        assertEquals(List.of(), resolver.getDependencies("consumer"));
        assertEquals(List.of(), resolver.getDependents("provider"));
        assertFalse(result.hasWrongVersionDependencies());
    }

    @Test
    void defaultExtensionFinderSwallowsMissingAndUnlinkableIndexedClasses() {
        var classLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
                if (name.equals("missing.Entrypoint")) {
                    throw new ClassNotFoundException(name);
                }
                if (name.equals("unlinkable.Entrypoint")) {
                    throw new NoClassDefFoundError("missing/Dependency");
                }
                return super.loadClass(name, resolve);
            }
        };
        var manager = new FixturePluginManager();
        manager.addStartedPlugin(descriptor("fixture", "1.0.0", ""), classLoader);
        var indexedFinder = new LegacyExtensionFinder(manager) {
            @Override
            public Map<String, Set<String>> readPluginsStorages() {
                return Map.of("fixture",
                    Set.of("missing.Entrypoint", "unlinkable.Entrypoint"));
            }

            @Override
            public Map<String, Set<String>> readClasspathStorages() {
                return Map.of();
            }
        };
        var finder = new IsolatedDefaultExtensionFinder(manager);
        finder.add(indexedFinder);

        List<ExtensionWrapper> extensions = finder.find("fixture");

        assertEquals(List.of(), extensions);
    }

    @Test
    void defaultVersionManagerUsesSemverRangesAndRejectsMalformedInput() {
        var versions = new DefaultVersionManager();
        var range = ">=1.0.0 & <2.0.0";

        assertTrue(versions.checkVersionConstraint("1.0.0", range));
        assertTrue(versions.checkVersionConstraint("1.9.9", range));
        assertFalse(versions.checkVersionConstraint("0.9.9", range));
        assertFalse(versions.checkVersionConstraint("2.0.0", range));
        assertThrows(RuntimeException.class,
            () -> versions.checkVersionConstraint("not-semver", range));
        assertThrows(RuntimeException.class,
            () -> versions.checkVersionConstraint("1.0.0", "not-a-constraint"));
    }

    private static DefaultPluginDescriptor descriptor(String id, String version,
                                                        String dependencies) {
        return new FixturePluginDescriptor(id, version, dependencies);
    }

    private static final class FixturePluginDescriptor extends DefaultPluginDescriptor {
        private FixturePluginDescriptor(String id, String version, String dependencies) {
            setPluginId(id);
            setPluginVersion(version);
            setDependencies(dependencies);
        }
    }

    private static final class FixturePluginManager extends DefaultPluginManager {
        private FixturePluginManager() {
            super(Path.of("."));
        }

        private void addStartedPlugin(DefaultPluginDescriptor descriptor,
                                      ClassLoader classLoader) {
            var wrapper = new PluginWrapper(this, descriptor, Path.of(descriptor.getPluginId()),
                classLoader);
            wrapper.setPluginState(PluginState.STARTED);
            plugins.put(descriptor.getPluginId(), wrapper);
            pluginClassLoaders.put(descriptor.getPluginId(), classLoader);
        }
    }

    private static final class IsolatedDefaultExtensionFinder extends DefaultExtensionFinder {
        private IsolatedDefaultExtensionFinder(FixturePluginManager manager) {
            super(manager);
            finders.clear();
        }
    }
}
