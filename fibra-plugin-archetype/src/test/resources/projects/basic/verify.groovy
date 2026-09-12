import java.util.zip.ZipFile

def project = new File(basedir, "project/sample-fibra-plugin")
def pom = new File(project, "pom.xml")
def jar = new File(project, "target/sample-fibra-plugin-1.0.0.jar")
def installation = new File(project, "target/sample-fibra-plugin-1.0.0-plugin")
assert pom.isFile()
assert jar.isFile()
assert installation.isDirectory()
assert installation.list().toList().sort() == ["lib", "plugin.properties"]
assert new File(installation, "lib").list().toList() == ["plugin.jar"]
assert new File(installation, "lib/plugin.jar").bytes == jar.bytes
def properties = new Properties()
new File(installation, "plugin.properties").withReader("UTF-8") { properties.load(it) }
assert properties == [formatVersion: "1", runtime: "java", payload: "lib/plugin.jar"]

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
    assert !entries.any { it == "plugin.properties" || it.endsWith("extensions.idx") }
    def manifest = zip.getInputStream(zip.getEntry("META-INF/fibra/plugin.yaml"))
        .getText("UTF-8")
    assert manifest.contains("id: sample-fibra-plugin")
    assert manifest.contains("version: 1.0.0")
    assert manifest.contains("entrypoint: org.example.fibra.FibraPluginEntrypoint")
    assert manifest.contains("requires: []")
    assert !manifest.contains('${')
}

return true
