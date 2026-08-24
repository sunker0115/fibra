import java.util.zip.ZipFile

def project = new File(basedir, "project/sample-fibra-plugin")
def rootPom = new File(project, "pom.xml")
assert rootPom.isFile()

def pomText = rootPom.getText("UTF-8")
assert !pomText.contains("<parent>")
assert !pomText.contains('${revision}')
assert !pomText.contains("target/classes")
assert !pomText.contains("systemPath")
assert !pomText.contains('\\${')

def contract = new File(project,
    "plugin-api/target/sample-fibra-plugin-contract-1.0.0.zip")
def plugin = new File(project,
    "plugin-impl/target/sample-fibra-plugin-1.0.0.zip")
def deployment = new File(project,
    "deployment/target/sample-fibra-plugin-deployment-1.0.0.zip")
assert contract.isFile()
assert plugin.isFile()
assert deployment.isFile()

new ZipFile(plugin).withCloseable { zip ->
    def entries = zip.entries().toList()*.name
    assert entries.contains("sample-fibra-plugin/plugin.properties")
    assert entries.contains("sample-fibra-plugin/lib/sample-fibra-plugin-1.0.0.jar")
    assert entries.findAll { it.startsWith("sample-fibra-plugin/lib/") && it.endsWith(".jar") }.size() == 1
    assert !zip.getInputStream(zip.getEntry("sample-fibra-plugin/plugin.properties"))
        .getText("ISO-8859-1").contains("Plugin-Class")
}

new ZipFile(deployment).withCloseable { zip ->
    def files = zip.entries().toList().findAll { !it.directory }*.name.sort()
    assert files == [
        "checksums.sha256",
        "config/fibra.yaml",
        "deployment.properties",
        "plugins/sample-fibra-plugin-1.0.0.zip",
        "plugins/sample-fibra-plugin-contract-1.0.0.zip"
    ]
}

return true
