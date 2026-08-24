package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EngineDependencyBoundaryTest {
    @Test
    void declaresEveryProductionDependencyDirectlyAndRemainsSpringFree() throws Exception {
        var dependencies = productionDependencies(moduleRoot().resolve("pom.xml"));

        assertEquals(List.of(
            "com.sstlfsj:fibra-api",
            "com.sstlfsj:fibra-core",
            "com.sstlfsj:fibra-loader-pf4j",
            "com.sstlfsj:fibra-loader-config",
            "org.pf4j:pf4j",
            "org.slf4j:slf4j-api"
        ), dependencies);
        assertFalse(dependencies.stream().anyMatch(value -> value.contains("spring")));
    }

    private static List<String> productionDependencies(Path pomPath) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var project = factory.newDocumentBuilder().parse(pomPath.toFile()).getDocumentElement();
        var dependencies = directChild(project, "dependencies");
        var result = new ArrayList<String>();
        for (var child = dependencies.getFirstChild(); child != null;
             child = child.getNextSibling()) {
            if (child instanceof Element dependency
                && "dependency".equals(dependency.getLocalName())
                && !"test".equals(directChildText(dependency, "scope"))) {
                result.add(directChildText(dependency, "groupId") + ":"
                    + directChildText(dependency, "artifactId"));
            }
        }
        return List.copyOf(result);
    }

    private static Element directChild(Element parent, String localName) {
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static String directChildText(Element parent, String localName) {
        var child = directChild(parent, localName);
        return child == null ? null : child.getTextContent().strip();
    }

    private static Path moduleRoot() {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        return current.getFileName().toString().equals("fibra-engine")
            ? current
            : current.resolve("fibra-engine");
    }
}
