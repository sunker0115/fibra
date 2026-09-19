import java.util.zip.ZipFile

def project = new File(basedir, "project/sample-fibra-plugin")
def pom = new File(project, "pom.xml")
def jar = new File(project, "target/sample-fibra-plugin-1.0.0.jar")
def installation = new File(project, "target/sample-fibra-plugin-1.0.0-plugin")
assert pom.isFile()
assert jar.isFile()
assert installation.isDirectory()
assert installation.list().toList().sort() == ["fibra-package.yaml", "lib"]
assert new File(installation, "lib").list().toList() == ["plugin.jar"]
assert new File(installation, "lib/plugin.jar").bytes == jar.bytes
def packageManifest = new File(installation, "fibra-package.yaml").getText("UTF-8")
assert packageManifest.contains("format: 1")
assert packageManifest.contains("id: sample-fibra-plugin")
assert packageManifest.contains("version: 1.0.0")
assert packageManifest.contains("runtime: java")
assert packageManifest.contains("payload: lib/plugin.jar")
assert packageManifest.contains("dependencies: []")
assert !packageManifest.contains('${')

def pomText = pom.getText("UTF-8")
assert !pomText.contains("<parent>")
assert !pomText.contains("pf4j")
assert pomText.contains("fibra-api")
assert pomText.contains("maven-assembly-plugin")

new ZipFile(jar).withCloseable { zip ->
    def entries = zip.entries().toList()*.name
    assert entries.contains("META-INF/fibra/plugin.yaml")
    assert entries.contains("org/example/fibra/FibraPluginEntrypoint.class")
    assert entries.contains("org/example/fibra/PluginConfig.class")
    assert !entries.any { it.startsWith("com/sstlfsj/fibra/") }
    assert !entries.any { it == "fibra-package.yaml" || it.endsWith("extensions.idx") }
    def manifest = zip.getInputStream(zip.getEntry("META-INF/fibra/plugin.yaml"))
        .getText("UTF-8")
    assert manifest.contains("entrypoint: org.example.fibra.FibraPluginEntrypoint")
    assert !manifest.contains("id:")
    assert !manifest.contains("version:")
    assert !manifest.contains("requires:")
    assert !manifest.contains('${')
}

return true
