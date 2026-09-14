package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/** prepare 期间使用的有效类名事实；不持有 Class、loader 或 entrypoint。 */
final class JavaClassIndex {
    private static final String VERSIONS = "META-INF/versions/";

    static void validate(Map<ArtifactId, JavaManifestReader.JavaPackage> packages,
                         ClassLoader parent, List<String> parentPackages) {
        packages.forEach((id, artifact) -> validate(id, artifact.jars(), parent, parentPackages));
    }

    private static void validate(ArtifactId owner, List<Path> jars,
                                 ClassLoader parent, List<String> parentPackages) {
        var classes = new LinkedHashMap<String, Path>();
        try {
            for (var path : jars) {
                try (var jar = new JarFile(path.toFile(), true, ZipFile.OPEN_READ, JarFile.runtimeVersion())) {
                    jar.versionedStream().filter(entry -> !entry.isDirectory()).forEach(entry -> {
                        var name = effectiveName(jar, entry.getName());
                        if (name == null || !name.endsWith(".class")) return;
                        var className = name.substring(0, name.length() - ".class".length())
                            .replace('/', '.');
                        if (className.equals("module-info") || className.endsWith(".module-info")) return;
                        try {
                            if (PluginClassLoader.isParentDefined(className, parent, parentPackages)) return;
                        } catch (LinkageError failure) {
                            throw new JavaRuntimeException(JavaRuntimePhase.LOAD, owner,
                                "cannot resolve parent-first Java class " + className, failure);
                        }
                        var previous = classes.putIfAbsent(className, path);
                        if (previous != null && !previous.equals(path)) {
                            throw conflict(owner, className, previous, path);
                        }
                    });
                }
            }
        } catch (JavaRuntimeException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new JavaRuntimeException(JavaRuntimePhase.LOAD, owner,
                "cannot index Java plugin classes", failure);
        }
    }

    private static String effectiveName(JarFile jar, String name) {
        if (name.startsWith(VERSIONS)) {
            if (!jar.isMultiRelease()) return null;
            var versionEnd = name.indexOf('/', VERSIONS.length());
            if (versionEnd < 0 || versionEnd + 1 == name.length()) return null;
            name = name.substring(versionEnd + 1);
        }
        return name.startsWith("META-INF/") ? null : name;
    }

    private static JavaRuntimeException conflict(ArtifactId root, String className,
                                                 Path first, Path second) {
        return new JavaRuntimeException(JavaRuntimePhase.LOAD, root,
            "Java root " + root.value() + " sees class " + className + " from both artifact "
                + root.value() + " JAR " + first + " and artifact "
                + root.value() + " JAR " + second, null);
    }
}
