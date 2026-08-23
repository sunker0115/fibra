package com.sstlfsj.fibra.loader.pf4j;

import org.pf4j.PluginDescriptor;

import java.nio.file.Path;
import java.util.List;

record InspectedPluginPackage(
    Path packageRoot,
    PluginDescriptor descriptor,
    Path mainJar,
    List<Path> classpath,
    String digest,
    List<String> entrypointClassNames
) {
    InspectedPluginPackage {
        classpath = List.copyOf(classpath);
        entrypointClassNames = List.copyOf(entrypointClassNames);
    }
}
