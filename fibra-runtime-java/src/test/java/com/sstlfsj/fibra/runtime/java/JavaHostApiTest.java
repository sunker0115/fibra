package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.*;
import fixture.hostapi.DocumentApi;
import fixture.hostapi.HostBusiness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class JavaHostApiTest {
    @TempDir Path work;

    static JavaApiContract documentContract() {
        return new JavaApiContract("fixture.document", "1", List.of(DocumentApi.class,
            DocumentApi.Descriptor.class, DocumentApi.Request.class, DocumentApi.Result.class));
    }

    @Test
    void descriptorImportsAreExplicitImmutableAndAllowContractOnlyFacets() throws Exception {
        var descriptor = read("hostApis:\n  - id: fixture.document\n    version: '1'\n");
        assertTrue(descriptor.entrypoint().isEmpty());
        assertEquals(Map.of("fixture.document", "1"), descriptor.hostApis());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.hostApis().put("other", "1"));
        assertEquals(Map.of(), read("{}\n").hostApis());
        assertEquals(Map.of("fixture.document", "1"), read("entrypoint: example.Entry\n"
            + "definitionId: document\nhostApis: [{id: fixture.document, version: '1'}]\n").hostApis());
    }

    @Test
    void malformedImportsAreRejectedByTheSingleDescriptorParser() throws Exception {
        for (var yaml : List.of("hostApis: null\n", "hostApis: {}\n", "hostApis: {api: 1}\n",
            "hostApis: {1: '1'}\n", "hostApis: {'': '1'}\n", "hostApis: {api: ''}\n",
            "hostApis: {api: '1', api: '2'}\n", "hostApis: {}\nhostApis: {}\n",
            "hostApis: []\nunknown: true\n", "entrypoint: x\nhostApis: []\n",
            "hostApis: [null]\n", "hostApis: [{id: 1, version: '1'}]\n",
            "hostApis: [{id: api, version: 1}]\n", "hostApis: [{id: api}]\n",
            "hostApis: [{id: api, version: '1', unknown: x}]\n",
            "hostApis: [{id: api, version: '1'}, {id: api, version: '1'}]\n")) {
            assertThrows(JavaRuntimeException.class, () -> read(yaml), yaml);
        }
    }

    @Test
    void importedClassesHaveHostIdentityAndImportsDoNotPropagateThroughDependencies() throws Exception {
        var apis = new JavaApiContracts(List.of(documentContract()));
        var imports = apis.bind(new ArtifactId("document"), Map.of("fixture.document", "1"));
        try (var imported = new PluginClassLoader(List.of(), getClass().getClassLoader(),
                 JavaApiContracts.defaultParentPackages(), imports, apis.names());
             var absent = new PluginClassLoader(List.of(), getClass().getClassLoader(),
                 JavaApiContracts.defaultParentPackages(), Map.of(), apis.names())) {
            absent.dependencies(List.of(imported));
            assertSame(DocumentApi.class, imported.loadClass(DocumentApi.class.getName(), true));
            assertSame(DocumentApi.Request.class, imported.loadClass(DocumentApi.Request.class.getName()));
            assertSame(DocumentApi.KIND, imported.loadClass(DocumentApi.class.getName()).getField("KIND").get(null));
            assertThrows(ClassNotFoundException.class, () -> absent.loadClass(DocumentApi.class.getName()));
            assertThrows(ClassNotFoundException.class, () -> imported.loadClass(HostBusiness.class.getName()));
        }
    }

    @Test
    void shadowsAreRejectedBeforeParentExclusionsIncludingEffectiveMultiReleaseClasses() throws Exception {
        var apis = new JavaApiContracts(List.of(documentContract()));
        for (var path : List.of(shadow(false), shadow(true))) {
            var error = assertThrows(JavaRuntimeException.class, () -> JavaClassIndex.validate(
                new ArtifactId("shadow"), List.of(path), getClass().getClassLoader(),
                JavaApiContracts.defaultParentPackages(), apis.names()));
            assertEquals(JavaRuntimePhase.LOAD, error.phase());
            assertTrue(error.getMessage().contains(DocumentApi.class.getName()));
        }
    }

    @Test
    void providerConfigurationHasOneOrderIndependentIdentity() {
        var api = documentContract();
        var provider = new JavaRuntimeProvider(List.of(), List.of(api));
        assertEquals(provider.contractIdentity(), new JavaRuntimeProvider(List.of(), List.of(api)).contractIdentity());
        assertNotEquals(provider.contractIdentity(), new JavaRuntimeProvider(List.of()).contractIdentity());
        assertNotEquals(provider.contractIdentity(), new JavaRuntimeProvider(List.of(), List.of(
            new JavaApiContract(api.id(), "2", api.classes()))).contractIdentity());
    }

    private JavaFacetDescriptor read(String yaml) throws Exception {
        var path = Files.createTempFile(work, "descriptor-", ".jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new JarEntry(JavaFacetDescriptorReader.LOCATION));
            jar.write(yaml.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        var facet = new PluginFacet(new FacetId("host"), FacetRole.HOST, JavaRuntimeProvider.RUNTIME_ID,
            new ExecutionTarget("host"), path, "a".repeat(64), List.of(), List.of());
        var resources = new JavaFacetDescriptorReader.Resources(new JavaFacetDescriptorReader.ResourceAccess());
        try { return new JavaFacetDescriptorReader().read(facet, resources); }
        finally { resources.close(); }
    }

    private Path shadow(boolean multiRelease) throws Exception {
        var path = Files.createTempFile(work, "shadow-", ".jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        try (var jar = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            var name = DocumentApi.class.getName().replace('.', '/') + ".class";
            jar.putNextEntry(new JarEntry((multiRelease ? "META-INF/versions/21/" : "") + name));
            jar.write(new byte[]{0});
            jar.closeEntry();
        }
        return path;
    }
}
