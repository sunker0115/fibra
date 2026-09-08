import java.util.zip.ZipFile

def project = new File(basedir, "project/sample-fibra-plugin")
def pom = new File(project, "pom.xml")
def jar = new File(project, "target/sample-fibra-plugin-1.0.0.jar")
assert pom.isFile()
assert jar.isFile()

def pomText = pom.getText("UTF-8")
assert !pomText.contains("<parent>")
assert !pomText.contains("pf4j")
assert pomText.contains("fibra-api")

new ZipFile(jar).withCloseable { zip ->
    def entries = zip.entries().toList()*.name
    assert entries.contains("META-INF/fibra/plugin.yaml")
    assert entries.contains("org/example/fibra/FibraPluginEntrypoint.class")
    assert entries.contains("org/example/fibra/PluginConfig.class")
    assert !entries.any { it == "plugin.properties" || it.endsWith("extensions.idx") }
    def manifest = zip.getInputStream(zip.getEntry("META-INF/fibra/plugin.yaml"))
        .getText("UTF-8")
    assert manifest.contains("id: sample-fibra-plugin")
    assert manifest.contains("entrypoint: org.example.fibra.FibraPluginEntrypoint")
}

return true
