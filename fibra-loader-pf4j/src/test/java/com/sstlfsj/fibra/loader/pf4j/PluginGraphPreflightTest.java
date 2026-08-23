package com.sstlfsj.fibra.loader.pf4j;

import com.sstlfsj.fibra.Context;
import example.fibra.plugin.FixtureEntrypoint;
import example.fibra.plugin.NoPublicConstructorEntrypoint;
import example.fibra.plugin.contract.Greeting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginGraphPreflightTest {
    private static final String INDEX = "META-INF/extensions.idx";

    @TempDir
    Path temp;

    private final PluginPackageInspector inspector = new PluginPackageInspector();
    private final PluginGraphPreflight preflight = new PluginGraphPreflight();
    private int rootIndex;

    @Test
    void rejectsMissingRequiredDependency() throws Exception {
        var consumer = plugin("consumer", "1.0.0", "missing@>=1.0.0", Map.of());

        assertStage(FibraArtifactErrorStage.RESOLVE,
            () -> preflight.validate(List.of(), List.of(consumer)));
    }

    @Test
    void rejectsDependencyCycle() throws Exception {
        var alpha = plugin("alpha", "1.0.0", "beta@>=1.0.0", Map.of());
        var beta = plugin("beta", "1.0.0", "alpha@>=1.0.0", Map.of());

        assertStage(FibraArtifactErrorStage.RESOLVE,
            () -> preflight.validate(List.of(), List.of(alpha, beta)));
    }

    @Test
    void rejectsWrongRequiredDependencyRange() throws Exception {
        var provider = plugin("provider", "2.0.0", "", Map.of());
        var consumer = plugin("consumer", "1.0.0", "provider@>=1.0.0 & <2.0.0",
            Map.of());

        assertStage(FibraArtifactErrorStage.RESOLVE,
            () -> preflight.validate(List.of(), List.of(provider, consumer)));
    }

    @Test
    void allowsMissingOptionalDependencyButRejectsExistingWrongRange() throws Exception {
        var consumer = plugin("consumer", "1.0.0", "provider?@>=1.0.0 & <2.0.0",
            Map.of());

        preflight.validate(List.of(), List.of(consumer));

        var provider = plugin("provider", "2.0.0", "", Map.of());
        assertStage(FibraArtifactErrorStage.RESOLVE,
            () -> preflight.validate(List.of(), List.of(provider, consumer)));
    }

    @Test
    void rejectsDuplicateCandidateId() throws Exception {
        var first = plugin("same", "1.0.0", "", Map.of());
        var second = plugin("same", "2.0.0", "", Map.of());

        assertStage(FibraArtifactErrorStage.VALIDATE,
            () -> preflight.validate(List.of(), List.of(first, second)));
    }

    @Test
    void singleCandidateCannotBreakInstalledDependentRange() throws Exception {
        var currentProvider = plugin("provider", "1.0.0", "", Map.of());
        var currentConsumer = plugin("consumer", "1.0.0",
            "provider@>=1.0.0 & <2.0.0", Map.of());
        var nextProvider = plugin("provider", "2.0.0", "", Map.of());

        assertStage(FibraArtifactErrorStage.RESOLVE,
            () -> preflight.validate(List.of(currentProvider, currentConsumer),
                List.of(nextProvider)));
    }

    @Test
    void associatedCandidatesMayFormAValidProspectiveGraph() throws Exception {
        var currentProvider = plugin("provider", "1.0.0", "", Map.of());
        var currentConsumer = plugin("consumer", "1.0.0",
            "provider@>=1.0.0 & <2.0.0", Map.of());
        var nextProvider = plugin("provider", "2.0.0", "", Map.of());
        var nextConsumer = plugin("consumer", "2.0.0",
            "provider@>=2.0.0 & <3.0.0", Map.of());

        var result = preflight.validate(List.of(currentProvider, currentConsumer),
            List.of(nextProvider, nextConsumer));

        assertEquals(List.of("provider", "consumer"), result.candidateArtifactIds());
    }

    @Test
    void returnsOldAndProspectiveDependentClosuresSeparately() throws Exception {
        var currentMiddle = plugin("middle", "1.0.0", "base?@>=2.0.0", Map.of());
        var currentTop = plugin("top", "1.0.0", "middle@>=1.0.0", Map.of());
        var nextBase = plugin("base", "2.0.0", "", Map.of());

        var result = preflight.validate(List.of(currentMiddle, currentTop), List.of(nextBase));

        assertEquals(List.of(), result.oldAffectedArtifactIds());
        assertEquals(List.of("base", "middle", "top"),
            result.prospectiveAffectedArtifactIds());
        assertEquals(List.of("base", "middle", "top"), result.affectedArtifactIds());
    }

    @Test
    void contractOnlyDependencyDoesNotInheritDependencyEntrypoint() throws Exception {
        var contract = plugin("contract", "1.0.0", "", Map.of());
        var provider = executable("provider", "1.0.0", "contract@>=1.0.0");
        var consumer = plugin("consumer", "1.0.0", "provider@>=1.0.0", Map.of());

        var result = preflight.validate(List.of(), List.of(contract, provider, consumer));

        assertEquals(List.of("provider"), result.executableArtifactIds());
    }

    @Test
    void rejectsMissingIndexedEntrypointClass() throws Exception {
        var plugin = plugin("broken", "1.0.0", "", Map.of(
            INDEX, PluginPackageFixtures.extensionIndex(List.of("missing.Entrypoint"))
        ));

        assertStage(FibraArtifactErrorStage.VALIDATE,
            () -> preflight.validate(List.of(), List.of(plugin)));
    }

    @Test
    void rejectsIndexedEntrypointWithLinkageError() throws Exception {
        var className = FixtureEntrypoint.class.getName();
        var classEntry = className.replace('.', '/') + ".class";
        var plugin = plugin("broken", "1.0.0", "", Map.of(
            INDEX, PluginPackageFixtures.extensionIndex(List.of(className)),
            classEntry, unlinkEntrypoint(PluginPackageFixtures.classBytes(FixtureEntrypoint.class))
        ));

        assertStage(FibraArtifactErrorStage.VALIDATE,
            () -> preflight.validate(List.of(), List.of(plugin)));
    }

    @Test
    void rejectsIndexedTypeThatIsNotFibraEntrypoint() throws Exception {
        var plugin = pluginWithClass("broken", "1.0.0", "", Greeting.class,
            Greeting.class.getName());

        assertStage(FibraArtifactErrorStage.VALIDATE,
            () -> preflight.validate(List.of(), List.of(plugin)));
    }

    @Test
    void rejectsIndexedTypeDefinedByParentClassLoader() throws Exception {
        var plugin = plugin("broken", "1.0.0", "", Map.of(
            INDEX, PluginPackageFixtures.extensionIndex(List.of(Context.class.getName()))
        ));

        assertStage(FibraArtifactErrorStage.VALIDATE,
            () -> preflight.validate(List.of(), List.of(plugin)));
    }

    @Test
    void rejectsMultipleEntrypointsAndEntrypointWithoutPublicNoArgConstructor()
        throws Exception {
        var multiple = plugin("multiple", "1.0.0", "", Map.of(
            INDEX, PluginPackageFixtures.extensionIndex(List.of(
                FixtureEntrypoint.class.getName(), Greeting.class.getName()))
        ));
        var noConstructor = pluginWithClass("no-constructor", "1.0.0", "",
            NoPublicConstructorEntrypoint.class,
            NoPublicConstructorEntrypoint.class.getName());

        assertStage(FibraArtifactErrorStage.VALIDATE,
            () -> preflight.validate(List.of(), List.of(multiple)));
        assertStage(FibraArtifactErrorStage.VALIDATE,
            () -> preflight.validate(List.of(), List.of(noConstructor)));
    }

    @Test
    void closesEveryTemporaryClassLoaderBeforeReturningOrThrowing() throws Exception {
        var classLoaders = new ArrayList<FibraPluginClassLoader>();
        var trackingPreflight = new PluginGraphPreflight(root ->
            new FibraDirectoryPluginManager(root) {
                @Override
                List<String> loadPluginsStrict(List<Path> pluginPaths) {
                    var ids = super.loadPluginsStrict(pluginPaths);
                    ids.stream()
                        .map(this::getPluginClassLoader)
                        .map(FibraPluginClassLoader.class::cast)
                        .forEach(classLoaders::add);
                    return ids;
                }
            });
        var provider = executable("provider", "1.0.0", "");
        var consumer = plugin("consumer", "1.0.0", "provider@>=1.0.0", Map.of());

        trackingPreflight.validate(List.of(), List.of(provider, consumer));

        var broken = plugin("broken", "1.0.0", "", Map.of(
            INDEX, PluginPackageFixtures.extensionIndex(List.of("missing.Entrypoint"))
        ));
        assertStage(FibraArtifactErrorStage.VALIDATE,
            () -> trackingPreflight.validate(List.of(), List.of(broken)));

        assertEquals(3, classLoaders.size());
        assertTrue(classLoaders.stream().allMatch(FibraPluginClassLoader::isClosed));
    }

    private InspectedPluginPackage executable(String id, String version,
                                               String dependencies) throws Exception {
        return pluginWithClass(id, version, dependencies, FixtureEntrypoint.class,
            FixtureEntrypoint.class.getName());
    }

    private InspectedPluginPackage pluginWithClass(String id, String version,
                                                   String dependencies, Class<?> type,
                                                   String indexedType) throws Exception {
        return plugin(id, version, dependencies, Map.of(
            INDEX, PluginPackageFixtures.extensionIndex(List.of(indexedType)),
            type.getName().replace('.', '/') + ".class", PluginPackageFixtures.classBytes(type)
        ));
    }

    private InspectedPluginPackage plugin(String id, String version, String dependencies,
                                          Map<String, byte[]> jarEntries) throws Exception {
        var parent = Files.createDirectory(temp.resolve("packages-" + rootIndex++));
        var packageRoot = PluginPackageFixtures.standardDirectory(parent, id, version);
        var properties = new LinkedHashMap<String, String>();
        properties.put("plugin.id", id);
        properties.put("plugin.version", version);
        if (!dependencies.isEmpty()) {
            properties.put("plugin.dependencies", dependencies);
        }
        PluginPackageFixtures.writeProperties(packageRoot, properties);
        PluginPackageFixtures.writeJar(
            packageRoot.resolve("lib").resolve(id + "-" + version + ".jar"), jarEntries);
        return inspector.inspectDirectory(packageRoot);
    }

    private static byte[] unlinkEntrypoint(byte[] original) {
        var source = "com/sstlfsj/fibra/pf4j/VoidFibraPluginEntrypoint"
            .getBytes(StandardCharsets.UTF_8);
        var missingName = "missing/" + "X".repeat(source.length - "missing/".length());
        var replacement = missingName.getBytes(StandardCharsets.UTF_8);
        var mutated = original.clone();
        var replacements = 0;
        for (int offset = 0; offset <= mutated.length - source.length; offset++) {
            var matches = true;
            for (int index = 0; index < source.length; index++) {
                if (mutated[offset + index] != source[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                System.arraycopy(replacement, 0, mutated, offset, replacement.length);
                replacements++;
            }
        }
        assertTrue(replacements > 0);
        return mutated;
    }

    private static void assertStage(FibraArtifactErrorStage stage, Runnable operation) {
        var exception = assertThrows(FibraArtifactException.class, operation::run);
        assertEquals(stage, exception.stage());
    }
}
