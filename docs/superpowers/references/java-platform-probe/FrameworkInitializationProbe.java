package com.sstlfsj.fibra.runtime.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Optional external-payload probe, compiled against the delivered runtime JAR. */
public final class FrameworkInitializationProbe {
    public static void main(String[] args) throws Exception {
        var jars = new ArrayList<java.net.URL>();
        for (var arg : args) jars.add(Path.of(arg).toUri().toURL());
        if (jars.isEmpty()) throw new IllegalArgumentException("framework JAR paths required");
        var parent = PluginClassLoader.class.getClassLoader();
        var original = Thread.currentThread().getContextClassLoader();
        // The runtime's host sharing preferences; this does not add a production entrypoint.
        var shared = List.of("java.", "javax.", "jdk.", "sun.", "com.sstlfsj.fibra.",
            "reactor.", "org.reactivestreams.", "org.slf4j.");
        for (var pluginContext : List.of(false, true)) {
            try (var loader = new PluginClassLoader(jars, parent, shared)) {
                try {
                    if (pluginContext) Thread.currentThread().setContextClassLoader(loader);
                    for (var platform : List.of(org.xml.sax.EntityResolver.class,
                        org.w3c.dom.Document.class, java.sql.Connection.class)) {
                        if (loader.loadClass(platform.getName()) != platform) {
                            throw new AssertionError("platform identity: " + platform.getName());
                        }
                    }
                    for (var name : List.of("org.apache.ibatis.session.Configuration",
                        "com.baomidou.mybatisplus.core.MybatisConfiguration")) {
                        try {
                            parent.loadClass(name);
                            throw new AssertionError("framework leaked onto host classpath: " + name);
                        } catch (ClassNotFoundException expected) {
                            // Frameworks must be defined by the real plugin loader.
                        }
                        var type = loader.loadClass(name);
                        if (type.getClassLoader() != loader) throw new AssertionError(name);
                        var configuration = type.getConstructor().newInstance();
                        System.out.println("INITIALIZED tccl=" + (pluginContext ? "plugin" : "host")
                            + " type=" + configuration.getClass().getName()
                            + " loader=" + type.getClassLoader().getClass().getName());
                    }
                } finally {
                    Thread.currentThread().setContextClassLoader(original);
                }
            }
        }
    }
}
