package com.sstlfsj.fibra.loader.pf4j;

import com.sstlfsj.fibra.Context;
import example.fibra.plugin.FixtureEntrypoint;
import example.fibra.plugin.contract.Greeting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class FibraPluginClassLoaderTest {
    @TempDir
    Path temp;

    @Test
    void keepsPdaForPluginClassesAndDelegatesSharedRuntimeToParent() throws Exception {
        var plugin = packageWithClass("alpha", "1.0.0", "", FixtureEntrypoint.class);
        var manager = new FibraDirectoryPluginManager(temp);
        manager.loadPluginsStrict(List.of(plugin));
        var classLoader = manager.getPluginClassLoader("alpha");

        var pluginType = classLoader.loadClass(FixtureEntrypoint.class.getName());

        assertNotSame(FixtureEntrypoint.class, pluginType);
        assertSame(classLoader, pluginType.getClassLoader());
        assertSame(Context.class, classLoader.loadClass(Context.class.getName()));
        manager.unloadPluginsStrict(List.of("alpha"));
    }

    @Test
    void isolatesPrivateDependencyCopiesBetweenPlugins() throws Exception {
        var first = packageWithClass("first", "1.0.0", "", FixtureEntrypoint.class);
        var second = packageWithClass("second", "2.0.0", "", FixtureEntrypoint.class);
        var manager = new FibraDirectoryPluginManager(temp);
        manager.loadPluginsStrict(List.of(first, second));

        var firstType = manager.getPluginClassLoader("first")
            .loadClass(FixtureEntrypoint.class.getName());
        var secondType = manager.getPluginClassLoader("second")
            .loadClass(FixtureEntrypoint.class.getName());

        assertNotSame(firstType, secondType);
        assertNotSame(firstType.getClassLoader(), secondType.getClassLoader());
        manager.unloadPluginsStrict(List.of("second", "first"));
    }

    @Test
    void providerAndConsumerResolveContractFromContractPluginClassLoader() throws Exception {
        var contract = packageWithClass("contract", "1.0.0", "", Greeting.class);
        var provider = PluginPackageFixtures.standardDirectory(temp, "provider", "1.0.0");
        PluginPackageFixtures.writeProperties(provider, Map.of(
            "plugin.id", "provider",
            "plugin.version", "1.0.0",
            "plugin.dependencies", "contract@>=1.0.0 & <2.0.0"
        ));
        var consumer = PluginPackageFixtures.standardDirectory(temp, "consumer", "1.0.0");
        PluginPackageFixtures.writeProperties(consumer, Map.of(
            "plugin.id", "consumer",
            "plugin.version", "1.0.0",
            "plugin.dependencies", "contract@>=1.0.0 & <2.0.0"
        ));
        var manager = new FibraDirectoryPluginManager(temp);
        manager.loadPluginsStrict(List.of(contract, provider, consumer));

        var contractType = manager.getPluginClassLoader("contract").loadClass(Greeting.class.getName());

        assertNotSame(Greeting.class, contractType);
        assertSame(contractType,
            manager.getPluginClassLoader("provider").loadClass(Greeting.class.getName()));
        assertSame(contractType,
            manager.getPluginClassLoader("consumer").loadClass(Greeting.class.getName()));
        manager.unloadPluginsStrict(List.of("consumer", "provider", "contract"));
    }

    private Path packageWithClass(String id, String version, String dependencies,
                                  Class<?> type) throws Exception {
        var plugin = PluginPackageFixtures.standardDirectory(temp, id, version);
        if (!dependencies.isEmpty()) {
            PluginPackageFixtures.writeProperties(plugin, Map.of(
                "plugin.id", id,
                "plugin.version", version,
                "plugin.dependencies", dependencies
            ));
        }
        var entry = type.getName().replace('.', '/') + ".class";
        PluginPackageFixtures.writeJar(plugin.resolve("lib").resolve(id + "-" + version + ".jar"),
            Map.of(entry, PluginPackageFixtures.classBytes(type)));
        return plugin;
    }
}
